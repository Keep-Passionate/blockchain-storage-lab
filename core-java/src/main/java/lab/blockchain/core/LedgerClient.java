/** 文件功能：定义离线教学账本接口；主实现位于 backend-java 的 Fabric Gateway 适配器。 */
package lab.blockchain.core;

import java.util.List;

/** Ledger port: production code can implement this with Fabric Gateway instead. */
public interface LedgerClient {
    Block append(TraceEvent event);
    List<Block> blocks();
    List<TraceEvent> findByTraceId(String traceId);
    boolean verify();
}
