/** 文件功能：定义后端创建溯源事件的输入 DTO，文件字节只在链下处理。 */
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
