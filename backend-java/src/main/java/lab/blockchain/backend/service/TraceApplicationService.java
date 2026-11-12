package lab.blockchain.backend.service;

import lab.blockchain.backend.dto.CreateTraceCommand;
import lab.blockchain.backend.port.LedgerPort;
import lab.blockchain.backend.port.ObjectStoragePort;
import lab.blockchain.core.Block;
import lab.blockchain.core.Hashing;
import lab.blockchain.core.TraceEvent;

import java.io.IOException;
import java.time.Instant;

/**
 * Framework-neutral orchestration example. A Spring @Service can delegate to
 * this class once the adapters are wired.
 */
public final class TraceApplicationService {
    private final ObjectStoragePort objects;
    private final LedgerPort ledger;

    public TraceApplicationService(ObjectStoragePort objects, LedgerPort ledger) {
        this.objects = objects;
        this.ledger = ledger;
    }

    public Block create(CreateTraceCommand command) throws IOException {
        var stored = objects.add(command.file());
        objects.pin(stored.cid());
        var metadata = String.join("|", command.traceId(), command.objectId(),
                command.stage().name(), command.operatorId(), command.description());
        var event = new TraceEvent(command.traceId(), command.objectId(), command.stage(),
                command.operatorId(), Instant.now(), stored.cid(), Hashing.sha256Hex(metadata),
                command.description());
        return ledger.submit(event);
    }
}
