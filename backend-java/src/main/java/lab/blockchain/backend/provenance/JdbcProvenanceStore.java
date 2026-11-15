/** 文件功能：用 JDBC 保存四层溯源投影、不可覆盖的原始请求及可重放的事件消费收据。 */
package lab.blockchain.backend.provenance;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lab.blockchain.core.Hashing;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 小规模教学实现：一个实例使用一个 JDBC 连接，方法串行执行，便于观察每次事务。
 * 初始化 DDL 由 migrations/002_provenance.sql 负责，本类不会修改数据库结构。
 * 表名只能来自内部常量；用户字段全部通过 PreparedStatement 参数绑定。
 */
public final class JdbcProvenanceStore implements ProvenanceStore {
    private static final Map<String, String> TABLES = Map.of(
            "plot", "p_plot", "batch", "p_batch", "operation", "p_operation",
            "inspection", "p_inspection", "review", "p_review", "audit", "p_audit",
            "alert", "p_alert");
    private static final Set<String> STATUSES = Set.of("PENDING", "COMMITTED", "REJECTED", "UNKNOWN");
    private final Connection connection;

    public JdbcProvenanceStore(String url, String user, String password) throws SQLException {
        connection = DriverManager.getConnection(url, user, password);
        connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
    }

    /** 请求先落库，后调用 IPFS/Fabric；已有回执不会因重试而退回 PENDING。 */
    @Override
    public synchronized boolean begin(String requestId, String kind, String recordId, String batchId,
                                   String payloadJson) throws Exception {
        requireId(requestId, "requestId");
        table(kind);
        Objects.requireNonNull(payloadJson, "payloadJson");
        return transaction(() -> {
            try (PreparedStatement query = prepare(
                    "SELECT record_kind,record_id,batch_id,raw_payload FROM p_submission WHERE request_id=? FOR UPDATE",
                    requestId); ResultSet row = query.executeQuery()) {
                if (row.next()) {
                    if (!kind.equals(row.getString(1)) || !Objects.equals(recordId, row.getString(2))
                            || !Objects.equals(batchId, row.getString(3)) || !payloadJson.equals(row.getString(4))) {
                        throw new IllegalStateException("REQUEST_ID_CONFLICT: " + requestId);
                    }
                    return false;
                }
            }
            update("INSERT INTO p_submission(request_id,record_kind,record_id,batch_id,raw_payload,"
                            + "payload_sha256,submission_status,reason_codes_json) VALUES(?,?,?,?,?,?,'PENDING','[]')",
                    requestId, kind, recordId, batchId, payloadJson, Hashing.sha256Hex(payloadJson));
            return true;
        });
    }

    /** COMMITTED 和 REJECTED 是终态；网络超时先写 UNKNOWN，查清后再更新为终态。 */
    @Override
    public synchronized void finish(String requestId, String status, String reasonCodes, String txId)
            throws Exception {
        if (!STATUSES.contains(status)) throw new IllegalArgumentException("INVALID_SUBMISSION_STATUS");
        JsonElement reasons = JsonParser.parseString(reasonCodes == null ? "[]" : reasonCodes);
        if (!reasons.isJsonArray()) throw new IllegalArgumentException("REASON_CODES_MUST_BE_ARRAY");
        for (JsonElement reason : reasons.getAsJsonArray()) {
            if (!reason.isJsonPrimitive() || !reason.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException("REASON_CODE_MUST_BE_STRING");
            }
        }
        String normalizedTx = txId == null || txId.isBlank() ? null : txId;
        if (status.equals("COMMITTED") && normalizedTx == null) {
            throw new IllegalArgumentException("COMMITTED_REQUIRES_TX_ID");
        }
        transaction(() -> {
            try (PreparedStatement query = prepare(
                    "SELECT submission_status,reason_codes_json,fabric_tx_id FROM p_submission WHERE request_id=? FOR UPDATE",
                    requestId); ResultSet row = query.executeQuery()) {
                if (!row.next()) throw new IllegalStateException("REQUEST_NOT_FOUND: " + requestId);
                String previous = row.getString(1);
                if (previous.equals("COMMITTED") || previous.equals("REJECTED")) {
                    if (!previous.equals(status) || !reasons.equals(JsonParser.parseString(row.getString(2)))
                            || !Objects.equals(normalizedTx, row.getString(3))) {
                        throw new IllegalStateException("TERMINAL_RECEIPT_CONFLICT: " + requestId);
                    }
                    return null;
                }
                if (previous.equals("UNKNOWN") && status.equals("PENDING")) {
                    throw new IllegalStateException("UNKNOWN_CANNOT_RETURN_TO_PENDING");
                }
            }
            update("UPDATE p_submission SET submission_status=?,reason_codes_json=?,fabric_tx_id=? WHERE request_id=?",
                    status, reasons.toString(), normalizedTx, requestId);
            return null;
        });
    }

    @Override
    public synchronized void project(JsonObject envelope) throws Exception {
        transaction(() -> { projectInsideTransaction(envelope); return null; });
    }

    /**
     * 消费顺序是：核对去重键 → 保存投影 → 保存事件收据 → 一次提交。
     * 任一步失败都回滚，所以断线后可以从 lastBlock 包含式重放。
     */
    @Override
    public synchronized boolean acceptEvent(long blockNumber, String txId, String eventName,
                                            JsonObject envelope) throws Exception {
        if (blockNumber < 0) throw new IllegalArgumentException("NEGATIVE_BLOCK_NUMBER");
        if (!txId.equals(text(envelope, "txId"))) throw new IllegalArgumentException("EVENT_TX_ID_MISMATCH");
        return transaction(() -> {
            try (PreparedStatement query = prepare(
                    "SELECT block_number,event_json FROM p_event_inbox WHERE fabric_tx_id=? AND event_name=? FOR UPDATE",
                    txId, eventName); ResultSet row = query.executeQuery()) {
                if (row.next()) {
                    if (row.getLong(1) != blockNumber || !envelope.equals(JsonParser.parseString(row.getString(2)))) {
                        throw new IllegalStateException("EVENT_REPLAY_CONFLICT: " + txId);
                    }
                    return false;
                }
            }
            projectInsideTransaction(envelope);
            update("INSERT INTO p_event_inbox(fabric_tx_id,event_name,block_number,record_kind,record_id,event_json) "
                            + "VALUES(?,?,?,?,?,?)", txId, eventName, blockNumber, text(envelope, "kind"),
                    text(envelope, "id"), envelope.toString());
            return true;
        });
    }

    /** 对每个实体显式列出 SQL，使字段与四层模型的关系可以直接阅读。 */
    private void projectInsideTransaction(JsonObject envelope) throws Exception {
        String kind = text(envelope, "kind");
        String id = text(envelope, "id");
        requireId(id, "id");
        String target = table(kind);
        JsonObject data = envelope.getAsJsonObject("data");
        String txId = text(envelope, "txId");
        String batchId = kind.equals("plot") ? "" : text(envelope, "batchId");
        String serialized = envelope.toString();
        try (PreparedStatement query = prepare("SELECT record_json FROM " + target + " WHERE " + kind + "_id=? FOR UPDATE", id);
             ResultSet row = query.executeQuery()) {
            if (row.next()) {
                if (!envelope.equals(JsonParser.parseString(row.getString(1)))) {
                    throw new IllegalStateException("PROJECTION_CONFLICT: " + kind + "/" + id);
                }
                return;
            }
        }
        // 全量保存 envelope，包含合约记录的真实提交身份，不把 operatorId 当作登录身份。
        switch (kind) {
            case "plot" -> update("INSERT INTO p_plot(plot_id,region_code,crop,variety,growth_stage,altitude_m,"
                            + "environment_text,record_json,fabric_tx_id) VALUES(?,?,?,?,?,?,?,?,?)", id,
                    text(data, "regionCode"), text(data, "crop"), text(data, "variety"), text(data, "growthStage"),
                    data.get("altitudeM").getAsBigDecimal(), text(data, "environment"), serialized, txId);
            case "batch" -> update("INSERT INTO p_batch(batch_id,region_code,production_year,sequence_no,plot_id,"
                            + "started_at,record_json,fabric_tx_id) VALUES(?,?,?,?,?,?,?,?)", id,
                    text(data, "regionCode"), data.get("year").getAsInt(), data.get("sequence").getAsInt(),
                    text(data, "plotId"), text(data, "startedAt"), serialized, txId);
            case "operation" -> update("INSERT INTO p_operation(operation_id,batch_id,plot_id,operation_type,performed_at,"
                            + "operator_id,description_text,rule_version,record_json,fabric_tx_id) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    id, batchId, text(data, "plotId"), text(data, "operationType"), text(data, "performedAt"),
                    text(data, "operatorId"), text(data, "description"), text(data, "ruleVersion"), serialized, txId);
            case "inspection" -> update("INSERT INTO p_inspection(inspection_id,batch_id,report_cid,report_sha256,conclusion,"
                            + "lab_name,tested_at,summary_text,record_json,fabric_tx_id) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    id, batchId, text(data, "reportCid"), text(data, "reportSha256"), text(data, "conclusion"),
                    text(data, "labName"), text(data, "testedAt"), text(data, "summary"), serialized, txId);
            case "review" -> update("INSERT INTO p_review(review_id,batch_id,inspection_id,decision,comment_text,"
                            + "record_json,fabric_tx_id) VALUES(?,?,?,?,?,?,?)", id, batchId,
                    text(data, "inspectionId"), text(data, "decision"), text(data, "comment"), serialized, txId);
            case "audit" -> update("INSERT INTO p_audit(audit_id,batch_id,record_ids_json,evidence_cid,evidence_sha256,conclusion,"
                            + "comment_text,record_json,fabric_tx_id) VALUES(?,?,?,?,?,?,?,?,?)", id, batchId,
                    data.getAsJsonArray("recordIds").toString(), text(data, "evidenceCid"), text(data, "evidenceSha256"),
                    text(data, "conclusion"), text(data, "comment"), serialized, txId);
            case "alert" -> update("INSERT INTO p_alert(alert_id,batch_id,source_kind,source_id,delivery_status,recipient,"
                            + "comment_text,record_json,fabric_tx_id) VALUES(?,?,?,?,?,?,?,?,?)", id, batchId,
                    text(data, "sourceKind"), text(data, "sourceId"), text(data, "deliveryStatus"),
                    text(data, "recipient"), text(data, "comment"), serialized, txId);
            default -> throw new IllegalArgumentException("UNSUPPORTED_KIND: " + kind);
        }
    }

    @Override
    public synchronized JsonObject receipt(String requestId) throws Exception {
        try (PreparedStatement query = prepare("SELECT * FROM p_submission WHERE request_id=?", requestId);
             ResultSet row = query.executeQuery()) {
            if (!row.next()) throw new IllegalStateException("REQUEST_NOT_FOUND: " + requestId);
            return receiptRow(row);
        }
    }

    /** 返回一致的数据库快照；公开展示应另做权限过滤，不能直接公开原始提交证据。 */
    @Override
    public synchronized JsonObject snapshot(String batchId) throws Exception {
        return transaction(() -> {
            JsonObject result = new JsonObject();
            JsonElement batch = record("batch", batchId);
            result.add("batch", batch);
            result.add("plot", batch.isJsonNull() ? JsonNull.INSTANCE
                    : record("plot", text(batch.getAsJsonObject().getAsJsonObject("data"), "plotId")));
            for (String kind : new String[]{"operation", "inspection", "review", "audit", "alert"}) {
                JsonArray rows = new JsonArray();
                try (PreparedStatement query = prepare("SELECT record_json FROM " + table(kind)
                        + " WHERE batch_id=? ORDER BY " + kind + "_id", batchId);
                     ResultSet row = query.executeQuery()) {
                    while (row.next()) rows.add(JsonParser.parseString(row.getString(1)));
                }
                result.add(kind + "s", rows);
            }
            JsonArray submissions = new JsonArray();
            try (PreparedStatement query = prepare("SELECT * FROM p_submission WHERE batch_id=? ORDER BY request_id", batchId);
                 ResultSet row = query.executeQuery()) {
                while (row.next()) submissions.add(receiptRow(row));
            }
            result.add("submissions", submissions);
            return result;
        });
    }

    @Override
    public synchronized long lastBlock() throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT COALESCE(MAX(block_number),0) FROM p_event_inbox");
             ResultSet row = query.executeQuery()) {
            row.next();
            return row.getLong(1);
        }
    }

    @Override
    public synchronized void close() throws SQLException {
        connection.close();
    }

    private JsonElement record(String kind, String id) throws SQLException {
        try (PreparedStatement query = prepare("SELECT record_json FROM " + table(kind) + " WHERE " + kind + "_id=?", id);
             ResultSet row = query.executeQuery()) {
            return row.next() ? JsonParser.parseString(row.getString(1)) : JsonNull.INSTANCE;
        }
    }

    private static JsonObject receiptRow(ResultSet row) throws SQLException {
        JsonObject result = new JsonObject();
        result.addProperty("requestId", row.getString("request_id"));
        result.addProperty("kind", row.getString("record_kind"));
        result.addProperty("recordId", row.getString("record_id"));
        result.addProperty("batchId", row.getString("batch_id"));
        result.addProperty("status", row.getString("submission_status"));
        result.add("reasonCodes", JsonParser.parseString(row.getString("reason_codes_json")));
        result.addProperty("payloadSha256", row.getString("payload_sha256"));
        result.addProperty("fabricTxId", row.getString("fabric_tx_id"));
        String raw = row.getString("raw_payload");
        result.addProperty("rawPayload", raw);
        try { result.add("payload", JsonParser.parseString(raw)); }
        catch (RuntimeException invalidJson) { result.add("payload", JsonNull.INSTANCE); }
        result.addProperty("createdAt", row.getTimestamp("created_at").toInstant().toString());
        result.addProperty("updatedAt", row.getTimestamp("updated_at").toInstant().toString());
        return result;
    }

    private static String table(String kind) {
        String value = TABLES.get(kind);
        if (value == null) throw new IllegalArgumentException("UNSUPPORTED_KIND: " + kind);
        return value;
    }

    private static String text(JsonObject object, String field) {
        if (object == null || !object.has(field) || object.get(field).isJsonNull()) {
            throw new IllegalArgumentException("MISSING_FIELD: " + field);
        }
        return object.get(field).getAsString();
    }

    private static void requireId(String value, String field) {
        if (value == null || !value.matches("[A-Za-z0-9_.-]{1,128}")) {
            throw new IllegalArgumentException("INVALID_ID: " + field);
        }
    }

    private PreparedStatement prepare(String sql, Object... values) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        try {
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            return statement;
        } catch (SQLException failure) {
            statement.close();
            throw failure;
        }
    }

    private void update(String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = prepare(sql, values)) { statement.executeUpdate(); }
    }

    /** 所有复合写操作用同一事务；异常时还原连接状态，不把半条记录留在数据库。 */
    private <T> T transaction(DatabaseWork<T> work) throws Exception {
        connection.setAutoCommit(false);
        try {
            T result = work.run();
            connection.commit();
            return result;
        } catch (Exception failure) {
            try { connection.rollback(); } catch (SQLException rollbackFailure) { failure.addSuppressed(rollbackFailure); }
            throw failure;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    @FunctionalInterface
    private interface DatabaseWork<T> { T run() throws Exception; }
}
