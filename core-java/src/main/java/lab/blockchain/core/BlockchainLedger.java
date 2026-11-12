package lab.blockchain.core;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal private ledger for learning. It is intentionally not a production
 * consensus network: one process owns the append operation and verification is
 * explicit. This makes the data flow visible before Fabric is introduced.
 */
public final class BlockchainLedger implements LedgerClient {
    private final List<Block> blocks = new ArrayList<>();

    public BlockchainLedger() {
        blocks.add(Block.create(0, Instant.EPOCH, "0", List.of()));
    }

    public synchronized Block append(TraceEvent event) {
        Block previous = blocks.get(blocks.size() - 1);
        Block next = Block.create(blocks.size(), Instant.now(), previous.hash(), List.of(event));
        blocks.add(next);
        return next;
    }

    public synchronized List<Block> blocks() {
        return List.copyOf(blocks);
    }

    public synchronized List<TraceEvent> findByTraceId(String traceId) {
        return blocks.stream()
                .flatMap(block -> block.events().stream())
                .filter(event -> event.traceId().equals(traceId))
                .toList();
    }

    public synchronized boolean verify() {
        for (int i = 0; i < blocks.size(); i++) {
            Block current = blocks.get(i);
            if (!current.recomputesToHash()) {
                return false;
            }
            if (i > 0 && !current.previousHash().equals(blocks.get(i - 1).hash())) {
                return false;
            }
        }
        return true;
    }
}
