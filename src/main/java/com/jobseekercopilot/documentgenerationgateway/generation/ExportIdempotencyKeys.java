package com.jobseekercopilot.documentgenerationgateway.generation;

import java.util.Objects;
import java.util.UUID;

/** Stable downstream replay keys for immutable document exports. */
public final class ExportIdempotencyKeys {
    private ExportIdempotencyKeys() {
    }

    public static String forOperationOutput(
            UUID operationId,
            Output output) {
        return Objects.requireNonNull(operationId, "operationId")
                + ":"
                + Objects.requireNonNull(output, "output").suffix;
    }

    public static String forDocument(UUID documentId) {
        return Objects.requireNonNull(documentId, "documentId")
                + ":document-export";
    }

    public enum Output {
        CV("cv-export"),
        COVER_LETTER("cover-letter-export");

        private final String suffix;

        Output(String suffix) {
            this.suffix = suffix;
        }
    }
}
