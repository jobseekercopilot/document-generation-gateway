package com.jobseekercopilot.documentgenerationgateway.logging;

import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;

public final class CorrelationIds {
    private static final Pattern SAFE_CORRELATION_ID =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}");

    private CorrelationIds() {
    }

    public static String currentOrNew() {
        return normalize(MDC.get(CorrelationIdFilter.MDC_KEY));
    }

    public static String normalize(String candidate) {
        return isValid(candidate)
                ? candidate
                : UUID.randomUUID().toString();
    }

    public static boolean isValid(String candidate) {
        return candidate != null
                && SAFE_CORRELATION_ID.matcher(candidate).matches();
    }
}
