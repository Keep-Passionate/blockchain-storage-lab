/** 文件功能：定义链下请求证据、Fabric 查询投影和事件消费进度的存储边界。 */
package lab.blockchain.backend.provenance;

import com.google.gson.JsonObject;

/**
 * 业务服务只依赖这个接口，不直接拼接 SQL。
 * submission 是需要备份的原始证据；project 保存的链上副本可以重放恢复。
 */
public interface ProvenanceStore extends AutoCloseable {
    /** 保存原始请求；新插入返回 true，完全相同的既有请求返回 false，供恢复路径识别。 */
    boolean begin(String requestId, String kind, String recordId, String batchId, String payloadJson)
            throws Exception;

    /** 更新回执；reasonCodes 必须为 JSON 字符串数组，未知提交结果使用 UNKNOWN。 */
    void finish(String requestId, String status, String reasonCodes, String txId) throws Exception;

    /** 保存已确认的链上事实；重复的同一事实可以重放，不允许覆盖不同事实。 */
    void project(JsonObject envelope) throws Exception;

    /** 在一个数据库事务中写投影和事件收据；已经处理过的事件返回 false。 */
    boolean acceptEvent(long blockNumber, String txId, String eventName, JsonObject envelope)
            throws Exception;

    JsonObject receipt(String requestId) throws Exception;

    /** 按批次返回四层实体和审核、审计、预警、提交回执，供教学演示查询。 */
    JsonObject snapshot(String batchId) throws Exception;

    /** 返回最后处理的区块号；从该区块包含式重放，避免遗漏同一区块的后续事件。 */
    long lastBlock() throws Exception;

    @Override
    void close() throws Exception;
}
