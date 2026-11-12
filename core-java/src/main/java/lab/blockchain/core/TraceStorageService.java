package lab.blockchain.core;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

/** Orchestrates the write/read path without hiding the two storage boundaries. */
public final class TraceStorageService {
    private final ObjectStore objects;
    private final LedgerClient ledger;

    public TraceStorageService(ObjectStore objects, LedgerClient ledger) {
        this.objects = objects;
        this.ledger = ledger;
    }

    public TraceRecord append(String traceId, String objectId, Stage stage, String operatorId,
                              String description, byte[] file) throws IOException {
        StoredObject stored = objects.put(file);
        objects.pin(stored.cid());
        String metadata = String.join("|", traceId, objectId, stage.name(), operatorId, description);
        TraceEvent event = new TraceEvent(traceId, objectId, stage, operatorId, Instant.now(),
                stored.cid(), Hashing.sha256Hex(metadata), description);
        Block block = ledger.append(event);
        return new TraceRecord(event, block.index(), block.hash(), objects.exists(event.contentCid()));
    }

    public List<TraceRecord> query(String traceId) {
        return ledger.findByTraceId(traceId).stream()
                .map(event -> findRecord(event))
                .toList();
    }

    public byte[] fetchFile(TraceEvent event) throws IOException {
        return objects.get(event.contentCid());
    }

    public boolean verifyLedger() {
        return ledger.verify();
    }

    private TraceRecord findRecord(TraceEvent event) {
        long blockIndex = ledger.blocks().stream()
                .filter(block -> block.events().contains(event))
                .mapToLong(Block::index)
                .findFirst()
                .orElseThrow();
        Block block = ledger.blocks().get((int) blockIndex);
        return new TraceRecord(event, blockIndex, block.hash(), objects.exists(event.contentCid()));
    }
}
