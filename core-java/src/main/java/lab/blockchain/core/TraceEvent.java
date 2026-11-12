package lab.blockchain.core;

import java.time.Instant;
import java.util.Objects;

/** Small on-chain fact: it stores metadata and a pointer, never the file bytes. */
public record TraceEvent(
        String traceId,
        String objectId,
        Stage stage,
        String operatorId,
        Instant eventTime,
        String contentCid,
        String metadataHash,
        String description
) {
    public TraceEvent {
        require(traceId, "traceId");
        require(objectId, "objectId");
        Objects.requireNonNull(stage, "stage");
        require(operatorId, "operatorId");
        Objects.requireNonNull(eventTime, "eventTime");
        require(contentCid, "contentCid");
        require(metadataHash, "metadataHash");
        require(description, "description");
    }

    public String canonical() {
        return String.join("|", traceId, objectId, stage.name(), operatorId,
                eventTime.toString(), contentCid, metadataHash, description);
    }

    public String eventHash() {
        return Hashing.sha256Hex(canonical());
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
