package lab.blockchain.backend.dto;

import lab.blockchain.core.Stage;

/** HTTP input shape; no file bytes are placed in the ledger DTO. */
public record CreateTraceCommand(
        String traceId,
        String objectId,
        Stage stage,
        String operatorId,
        String description,
        byte[] file
) {}
