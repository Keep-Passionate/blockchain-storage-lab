/** 文件功能：编排请求回执、链码校验、报告存储和数据库投影，展示成功及失败的数据去向。 */
package lab.blockchain.backend.provenance;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import lab.blockchain.backend.port.ObjectStoragePort;
import lab.blockchain.core.Hashing;

public final class ProvenanceService {
    private final ProvenanceLedger ledger;
    private final ObjectStoragePort objects;
    private final ProvenanceStore store;

    public ProvenanceService(ProvenanceLedger ledger, ObjectStoragePort objects, ProvenanceStore store) {
        this.ledger = ledger;
        this.objects = objects;
        this.store = store;
    }

    /** 回执 ID 是本系统请求号；只有 COMMITTED 回执才能声明链上成功。 */
    public JsonObject write(String requestId, String kind, JsonObject data) throws Exception {
        return write(requestId, kind, data, null);
    }

    /** 检测报告原始字节保存在 IPFS；链上记录独立保存 CID 和 SHA-256。 */
    public JsonObject inspection(String requestId, JsonObject fields, byte[] report) throws Exception {
        if (report == null || report.length == 0) throw new IllegalArgumentException("EMPTY_REPORT");
        JsonObject request = fields.deepCopy();
        request.addProperty("reportSha256", Hashing.sha256Hex(report));
        return write(requestId, "inspection", request, report);
    }

    private JsonObject write(String requestId, String kind, JsonObject input, byte[] report) throws Exception {
        ProvenanceRules.requireSynthetic(input);
        String id = ProvenanceRules.id(kind, input);
        String batchId = kind.equals("plot") ? "" : ProvenanceRules.text(input, "batchId");
        // 原始请求先持久化。若链/网络失败，至少保留调查和对账入口。
        boolean firstAttempt = store.begin(requestId, kind, id, batchId, input.toString());
        JsonObject previous = store.receipt(requestId);
        if (!firstAttempt) {
            if (previous.get("status").getAsString().equals("REJECTED")) return previous;
            // 即使数据库停在PENDING，链上也可能已成功。先对账，不能因RECORD_EXISTS把成功写成拒绝。
            try {
                JsonObject existing = ledger.read(kind, id);
                JsonObject body = existing.getAsJsonObject("data").deepCopy();
                if (report != null) body.remove("reportCid");
                if (!body.equals(input)) throw new IllegalStateException("RECONCILIATION_PAYLOAD_CONFLICT");
                store.finish(requestId, "COMMITTED", "[]", existing.get("txId").getAsString());
                store.project(existing);
                return store.receipt(requestId);
            } catch (Exception failure) {
                if (!previous.get("status").getAsString().equals("COMMITTED")) {
                    store.finish(requestId, "UNKNOWN", "[\"RECONCILIATION_REQUIRED\"]", null);
                }
                throw failure;
            }
        }
        JsonObject data = input.deepCopy();
        JsonObject committed;
        try {
            if (kind.equals("operation")) {
                JsonObject validation = ledger.checkOperation(data);
                if (!validation.get("valid").getAsBoolean()) {
                    store.finish(requestId, "REJECTED", validation.get("reasonCodes").toString(), null);
                    return store.receipt(requestId);
                }
            }
            if (report != null) {
                var stored = objects.add(report);
                objects.pin(stored.cid());
                data.addProperty("reportCid", stored.cid());
            }
            committed = ledger.submit(kind, data);
        } catch (Exception failure) {
            // 网络失败不等于业务违规，也可能是提交成功但回执丢失；禁止自动当成功/自动重发。
            store.finish(requestId, "UNKNOWN", "[\"LEDGER_OR_STORAGE_FAILURE\"]", null);
            throw failure;
        }
        String txId = committed.get("txId").getAsString();
        store.finish(requestId, "COMMITTED", "[]", txId);
        // 跨系统无法一次原子提交。若此步失败，COMMITTED 回执保留，rebuild 可恢复投影。
        store.project(committed);
        return store.receipt(requestId);
    }

    /** 直接从链读报告锚点，不能信任被修改的数据库副本。 */
    public byte[] verifiedReport(String inspectionId) throws Exception {
        JsonObject data = ledger.read("inspection", inspectionId).getAsJsonObject("data");
        byte[] report = objects.cat(data.get("reportCid").getAsString());
        if (!Hashing.sha256Hex(report).equals(data.get("reportSha256").getAsString())) {
            throw new IllegalStateException("REPORT_HASH_MISMATCH");
        }
        return report;
    }

    /** 重新投影只补数据库读模型，绝不清空链下唯一的拒绝证据和请求回执。 */
    public JsonObject rebuild(String batchId) throws Exception {
        JsonArray records = ledger.listBatch(batchId);
        for (var record : records) store.project(record.getAsJsonObject());
        return store.snapshot(batchId);
    }
}
