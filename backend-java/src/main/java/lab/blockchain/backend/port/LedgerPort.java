package lab.blockchain.backend.port;

import lab.blockchain.core.Block;
import lab.blockchain.core.TraceEvent;

import java.util.List;

/** The business layer can switch from the local ledger to Fabric Gateway. */
public interface LedgerPort {
    Block submit(TraceEvent event);
    List<TraceEvent> query(String traceId);
}
