package com.jobseekercopilot.documentgenerationgateway.generation;

import com.jobseekercopilot.documentgenerationgateway.exception.GenerationDeadlineExceededException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

@Component
public class OperationDeadlineGuard {
    private final Clock clock;
    private final ThreadLocal<Instant> activeDeadline = new ThreadLocal<>();

    public OperationDeadlineGuard() {
        this(Clock.systemUTC());
    }

    OperationDeadlineGuard(Clock clock) {
        this.clock = Objects.requireNonNull(clock);
    }

    public <T> T call(Instant deadline, Supplier<T> downstreamCall) {
        Objects.requireNonNull(deadline);
        Objects.requireNonNull(downstreamCall);
        requireRemaining(deadline, false, null);
        Instant previous = activeDeadline.get();
        activeDeadline.set(deadline);
        try {
            T result;
            try {
                result = downstreamCall.get();
            } catch (RuntimeException failure) {
                requireRemaining(deadline, true, failure);
                throw failure;
            }
            requireRemaining(deadline, true, null);
            return result;
        } finally {
            if (previous == null) {
                activeDeadline.remove();
            } else {
                activeDeadline.set(previous);
            }
        }
    }

    public void run(Instant deadline, Runnable downstreamCall) {
        call(deadline, () -> {
            downstreamCall.run();
            return null;
        });
    }

    public Duration remainingOr(Duration maximum) {
        requirePositive(maximum, "maximum downstream timeout");
        Instant deadline = activeDeadline.get();
        if (deadline == null) {
            return maximum;
        }
        Duration remaining = Duration.between(clock.instant(), deadline);
        if (remaining.isZero() || remaining.isNegative()) {
            throw new GenerationDeadlineExceededException(true, null);
        }
        return remaining.compareTo(maximum) < 0 ? remaining : maximum;
    }

    private void requireRemaining(
            Instant deadline,
            boolean downstreamCallStarted,
            Throwable cause) {
        if (!clock.instant().isBefore(deadline)) {
            throw new GenerationDeadlineExceededException(
                    downstreamCallStarted, cause);
        }
    }

    private static void requirePositive(Duration value, String label) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalStateException(label + " must be positive.");
        }
    }
}
