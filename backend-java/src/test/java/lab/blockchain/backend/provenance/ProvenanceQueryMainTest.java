/** 文件功能：验证查询的只读边界、链上哈希来源、输出脱敏和补投影语义；不代替真实集成测试。 */
package lab.blockchain.backend.provenance;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import lab.blockchain.backend.port.ObjectStoragePort;
import lab.blockchain.core.Hashing;
import lab.blockchain.core.StoredObject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ProvenanceQueryMainTest {
    private static final String BATCH = "TEA-2026-000001";
    private static final byte[] REPORT = "synthetic report".getBytes(StandardCharsets.UTF_8);
    private static final byte[] AUDIT = "synthetic audit evidence".getBytes(StandardCharsets.UTF_8);

    @Test void defaultQueryNeverWritesAndDoesNotTrustDatabaseHashOrPrintIdentity() throws Exception {
        Fixture fixture = new Fixture();
        fixture.store.records.putAll(fixture.ledger.records);
        JsonObject altered = fixture.store.records.get("inspection/I1").deepCopy();
        altered.getAsJsonObject("data").addProperty("reportSha256", "0".repeat(64));
        fixture.store.records.put("inspection/I1", altered);
        JsonObject result = ProvenanceQueryMain.query(BATCH, false, fixture.ledger, fixture.store, fixture.objects);
        assertFalse(result.get("projectionMatchesLedger").getAsBoolean());
        assertTrue(result.get("objectHashesMatch").getAsBoolean(), "必须与链上摘要比对，不能信任数据库副本");
        assertEquals(0, fixture.store.projectCalls);
        assertFalse(result.toString().contains("PRIVATE_TEST_IDENTITY"));
        assertFalse(result.toString().contains("PRIVATE_REQUEST_CONTENT"));
    }

    @Test void missingObjectsAndChangedBytesHaveDifferentResults() throws Exception {
        Fixture fixture = new Fixture();
        fixture.store.records.putAll(fixture.ledger.records);
        fixture.objects.files.put("report-cid", new byte[]{1});
        fixture.objects.files.remove("audit-cid");
        JsonObject result = ProvenanceQueryMain.query(BATCH, false, fixture.ledger, fixture.store, fixture.objects);
        assertFalse(result.get("checksPassed").getAsBoolean());
        assertEquals("HASH_MISMATCH", result.getAsJsonArray("objectChecks").get(0).getAsJsonObject().get("status").getAsString());
        assertEquals("UNAVAILABLE", result.getAsJsonArray("objectChecks").get(1).getAsJsonObject().get("status").getAsString());
    }

    @Test void rebuildRestoresProjectionWithoutChangingOriginalSubmissionEvidence() throws Exception {
        Fixture fixture = new Fixture();
        JsonArray before = fixture.store.receipts.deepCopy();
        JsonObject result = ProvenanceQueryMain.query(BATCH, true, fixture.ledger, fixture.store, fixture.objects);
        assertTrue(result.get("checksPassed").getAsBoolean());
        assertEquals(3, fixture.store.projectCalls);
        assertEquals(before, fixture.store.receipts);
    }

    private static JsonObject envelope(String kind, String id) {
        JsonObject record = new JsonObject();
        record.addProperty("kind", kind); record.addProperty("id", id); record.addProperty("batchId", BATCH);
        record.addProperty("txId", "tx-" + id); record.addProperty("submitterId", "PRIVATE_TEST_IDENTITY");
        JsonObject data = new JsonObject(); data.addProperty(kind + "Id", id); data.addProperty("batchId", BATCH);
        record.add("data", data); return record;
    }

    private static final class Fixture {
        final ReadLedger ledger = new ReadLedger(); final ReadStore store = new ReadStore(); final ReadObjects objects = new ReadObjects();
        Fixture() {
            ledger.records.put("batch/" + BATCH, envelope("batch", BATCH));
            JsonObject report = envelope("inspection", "I1");
            report.getAsJsonObject("data").addProperty("reportCid", "report-cid");
            report.getAsJsonObject("data").addProperty("reportSha256", Hashing.sha256Hex(REPORT));
            ledger.records.put("inspection/I1", report);
            JsonObject audit = envelope("audit", "A1");
            audit.getAsJsonObject("data").addProperty("evidenceCid", "audit-cid");
            audit.getAsJsonObject("data").addProperty("evidenceSha256", Hashing.sha256Hex(AUDIT));
            ledger.records.put("audit/A1", audit);
            objects.files.put("report-cid", REPORT); objects.files.put("audit-cid", AUDIT);
            JsonObject receipt = new JsonObject(); receipt.addProperty("requestId", "REQ-1");
            receipt.addProperty("status", "REJECTED"); receipt.addProperty("rawPayload", "PRIVATE_REQUEST_CONTENT");
            store.receipts.add(receipt);
        }
    }

    /** 所有不允许的写接口主动失败，保证查询测试不是只检查输出。 */
    private static final class ReadLedger implements ProvenanceLedger {
        final Map<String, JsonObject> records = new LinkedHashMap<>();
        public JsonArray listBatch(String batchId) { JsonArray a = new JsonArray(); records.values().forEach(a::add); return a; }
        public JsonObject submit(String kind, JsonObject data) { throw new AssertionError("query must not submit"); }
        public JsonObject checkOperation(JsonObject data) { throw new AssertionError("unexpected validation"); }
        public JsonObject read(String kind, String id) { throw new AssertionError("use ListBatch snapshot"); }
        public void close() { }
    }

    private static final class ReadObjects implements ObjectStoragePort {
        final Map<String, byte[]> files = new LinkedHashMap<>();
        public byte[] cat(String cid) throws IOException {
            if (!files.containsKey(cid)) throw new IOException("unavailable"); return files.get(cid);
        }
        public StoredObject add(byte[] bytes) { throw new AssertionError("query must not upload"); }
        public void pin(String cid) { throw new AssertionError("query must not pin"); }
    }

    private static final class ReadStore implements ProvenanceStore {
        final Map<String, JsonObject> records = new LinkedHashMap<>(); final JsonArray receipts = new JsonArray(); int projectCalls;
        public void project(JsonObject record) { projectCalls++; records.put(record.get("kind").getAsString() + "/" + record.get("id").getAsString(), record); }
        public JsonObject snapshot(String batchId) {
            JsonObject result = new JsonObject(); result.add("batch", records.get("batch/" + batchId)); result.add("plot", null);
            for (String kind : new String[]{"operation", "inspection", "review", "audit", "alert"}) {
                JsonArray rows = new JsonArray(); records.forEach((key, record) -> { if (key.startsWith(kind + "/")) rows.add(record); });
                result.add(kind + "s", rows);
            }
            result.add("submissions", receipts); return result;
        }
        public boolean begin(String a, String b, String c, String d, String e) { throw new AssertionError("query must not begin request"); }
        public void finish(String a, String b, String c, String d) { throw new AssertionError("query must not change receipt"); }
        public boolean acceptEvent(long a, String b, String c, JsonObject d) { throw new AssertionError("query must not consume event"); }
        public JsonObject receipt(String id) { throw new AssertionError("unexpected receipt lookup"); }
        public long lastBlock() { throw new AssertionError("unexpected checkpoint lookup"); }
        public void close() { }
    }
}
