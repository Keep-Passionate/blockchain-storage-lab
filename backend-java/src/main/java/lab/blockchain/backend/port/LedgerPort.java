/** 文件功能：定义业务层提交和查询 Fabric 账本的端口。 */
package lab.blockchain.backend.port;

import lab.blockchain.core.TraceEvent;

import java.io.IOException;
import java.util.List;

/**
 * 业务层只依赖这个端口，具体实现直接使用 Fabric Gateway。
 * 本地教学账本可以作为离线对照实现，但不属于主链路。
 */
public interface LedgerPort {
    void create(TraceEvent event) throws IOException;

    void append(TraceEvent event) throws IOException;

    List<TraceEvent> query(String traceId) throws IOException;
}
