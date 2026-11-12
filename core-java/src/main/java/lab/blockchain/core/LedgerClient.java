package lab.blockchain.core;

import java.util.List;

/** Ledger port: production code can implement this with Fabric Gateway instead. */
public interface LedgerClient {
    Block append(TraceEvent event);
    List<Block> blocks();
    List<TraceEvent> findByTraceId(String traceId);
    boolean verify();
}
