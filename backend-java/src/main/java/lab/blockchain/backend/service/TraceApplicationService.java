/** 文件功能：编排后端创建溯源记录的链下存储与链上提交顺序。 */
package lab.blockchain.backend.service;

import lab.blockchain.backend.dto.CreateTraceCommand;
import lab.blockchain.backend.port.LedgerPort;
import lab.blockchain.backend.port.ObjectStoragePort;
import lab.blockchain.core.Hashing;
import lab.blockchain.core.StoredObject;
import lab.blockchain.core.TraceEvent;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

/**
 * 文件功能：编排“先写 IPFS、再把 CID 写入 Fabric”的主业务流程。
 * Spring Controller 只需要调用本类，不直接接触 Fabric 或 IPFS SDK。
 */
public final class TraceApplicationService {
    private final ObjectStoragePort objects;
    private final LedgerPort ledger;

    public TraceApplicationService(ObjectStoragePort objects, LedgerPort ledger) {
        this.objects = objects;
        this.ledger = ledger;
    }

    public TraceWriteResult create(CreateTraceCommand command) throws IOException {
        return write(command, false);
    }

    /** 向已有 trace 追加下一个茶叶生产阶段事件。 */
    public TraceWriteResult append(CreateTraceCommand command) throws IOException {
        return write(command, true);
    }

    private TraceWriteResult write(CreateTraceCommand command, boolean append) throws IOException {
        StoredObject stored = objects.add(command.file());
        objects.pin(stored.cid());
        var metadata = String.join("|", command.traceId(), command.objectId(),
                command.stage().name(), command.operatorId(), command.description());
        var event = new TraceEvent(command.traceId(), command.objectId(), command.stage(),
                command.operatorId(), Instant.now(), stored.cid(), Hashing.sha256Hex(metadata),
                command.description());
        if (append) {
            ledger.append(event);
        } else {
            ledger.create(event);
        }
        return new TraceWriteResult(event, stored);
    }

    /** 查询 Fabric 中的事件；文件仍然要根据事件中的 CID 从 IPFS 读取。 */
    public List<TraceEvent> query(String traceId) throws IOException {
        return ledger.query(traceId);
    }

    /** 根据 Fabric 事件中的 CID 从 IPFS 取回原始文件字节。 */
    public byte[] fetch(TraceEvent event) throws IOException {
        return objects.cat(event.contentCid());
    }
}
