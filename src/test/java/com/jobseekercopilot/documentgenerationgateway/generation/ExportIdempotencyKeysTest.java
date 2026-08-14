package com.jobseekercopilot.documentgenerationgateway.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ExportIdempotencyKeysTest {
    @Test
    void replayKeepsTheSameOperationOutputKey() {
        UUID operationId = UUID.randomUUID();

        String first = ExportIdempotencyKeys.forOperationOutput(
                operationId,
                ExportIdempotencyKeys.Output.CV);
        String replay = ExportIdempotencyKeys.forOperationOutput(
                operationId,
                ExportIdempotencyKeys.Output.CV);

        assertEquals(first, replay);
        assertEquals(operationId + ":cv-export", first);
    }

    @Test
    void distinctOutputsAndDocumentsCannotShareAKey() {
        UUID operationId = UUID.randomUUID();
        UUID firstDocumentId = UUID.randomUUID();
        UUID secondDocumentId = UUID.randomUUID();

        assertNotEquals(
                ExportIdempotencyKeys.forOperationOutput(
                        operationId,
                        ExportIdempotencyKeys.Output.CV),
                ExportIdempotencyKeys.forOperationOutput(
                        operationId,
                        ExportIdempotencyKeys.Output.COVER_LETTER));
        assertNotEquals(
                ExportIdempotencyKeys.forDocument(firstDocumentId),
                ExportIdempotencyKeys.forDocument(secondDocumentId));
    }
}
