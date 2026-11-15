/** 文件功能：验证业务失败边界；内存替身仅用于单元测试，不能证明真实Fabric/IPFS连通。 */
package lab.blockchain.backend.provenance;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lab.blockchain.backend.port.ObjectStoragePort;
import lab.blockchain.core.Hashing;
import lab.blockchain.core.StoredObject;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ProvenanceServiceTest {
    final FakeLedger ledger = new FakeLedger();
    final FakeObjects objects = new FakeObjects();
    final FakeStore store = new FakeStore();
    final ProvenanceService service = new ProvenanceService(ledger, objects, store);

    @Test void batchNumberHasOneCanonicalRepresentation() {
        assertEquals("DEMOTEA-2026-000017", ProvenanceRules.batchId("DEMOTEA", 2026, 17));
        assertThrows(IllegalArgumentException.class, () -> ProvenanceRules.batchId("a/b", 2026, 1));
        assertThrows(IllegalArgumentException.class, () -> ProvenanceRules.batchId("TEA", 2026, 1000000));
    }
    @Test void rejectedOperationIsJournalledWithoutSubmittingOrUploading() throws Exception {
        ledger.valid = false;
        JsonObject receipt = service.write("REQ-1", "operation", data("operationId", "OP-1"));
        assertEquals("REJECTED", receipt.get("status").getAsString());
        assertTrue(receipt.get("reasonCodes").toString().contains("OPERATION_TYPE_NOT_ALLOWED"));
        assertEquals(0, ledger.submissions);
        assertEquals(0, objects.uploads);
        assertFalse(receipt.has("fabricTxId"));
        assertEquals("OP-1", receipt.getAsJsonObject("payload").get("operationId").getAsString());
    }
    @Test void reportHashAndCidRemainDifferentAndVerifiedAgainstLedger() throws Exception {
        byte[] report = "SYNTHETIC REPORT".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        service.inspection("REQ-1", data("inspectionId", "INS-1"), report);
        assertEquals("fake-cid-unit-test-only", ledger.last.getAsJsonObject("data").get("reportCid").getAsString());
        assertEquals(Hashing.sha256Hex(report), ledger.last.getAsJsonObject("data").get("reportSha256").getAsString());
        assertArrayEquals(report, service.verifiedReport("INS-1"));
        objects.bytes = new byte[]{1, 2, 3};
        assertEquals("REPORT_HASH_MISMATCH", assertThrows(IllegalStateException.class,
                () -> service.verifiedReport("INS-1")).getMessage());
    }
    @Test void networkUncertaintyMustNotBecomeRejectionOrSuccess() throws Exception {
        ledger.fail = true;
        assertThrows(IOException.class, () -> service.write("REQ-1", "operation", data("operationId", "OP-1")));
        assertEquals("UNKNOWN", store.receipt("REQ-1").get("status").getAsString());
        ledger.fail = false;
        assertThrows(Exception.class, () -> service.write("REQ-1", "operation", data("operationId", "OP-1")));
        assertEquals(1, ledger.submissions, "UNKNOWN请求不能盲目自动重发");
    }
    @Test void projectionFailureMustKeepCommittedReceipt() throws Exception {
        store.failProject = true;
        assertThrows(IOException.class, () -> service.write("REQ-1", "operation", data("operationId", "OP-1")));
        assertEquals("COMMITTED", store.receipt("REQ-1").get("status").getAsString());
        assertEquals("test-tx", store.receipt("REQ-1").get("fabricTxId").getAsString());
        store.failProject = false;
        service.rebuild("TEA-2026-000001");
        assertEquals(1, store.projected);
    }
    @Test void completedRequestIsIdempotentAndChangedPayloadCannotReplaceEvidence() throws Exception {
        JsonObject input = data("operationId", "OP-1");
        service.write("REQ-1", "operation", input);
        service.write("REQ-1", "operation", input);
        assertEquals(1, ledger.submissions);
        input.addProperty("description", "changed");
        assertThrows(IllegalArgumentException.class, () -> service.write("REQ-1", "operation", input));
        assertEquals(1, ledger.submissions);
    }
    @Test void uploadFailureLeavesInvestigableJournal() throws Exception {
        objects.fail = true;
        assertThrows(IOException.class, () -> service.inspection("REQ-1", data("inspectionId", "INS-1"), new byte[]{1}));
        assertEquals("UNKNOWN", store.receipt("REQ-1").get("status").getAsString());
        assertEquals(0, ledger.submissions);
    }
    @Test void crashAfterLedgerCommitRecoversPendingWithoutResubmission() throws Exception {
        store.failFinish = true;
        assertThrows(IOException.class, () -> service.write("REQ-1", "operation", data("operationId", "OP-1")));
        assertEquals("PENDING", store.receipt("REQ-1").get("status").getAsString());
        store.failFinish = false;
        service.write("REQ-1", "operation", data("operationId", "OP-1"));
        assertEquals("COMMITTED", store.receipt("REQ-1").get("status").getAsString());
        assertEquals(1, ledger.submissions);
    }
    private JsonObject data(String key, String id) {
        JsonObject d = new JsonObject();
        d.addProperty("schemaVersion", 1); d.addProperty("synthetic", true);
        d.addProperty(key, id); d.addProperty("batchId", "TEA-2026-000001");
        return d;
    }

    static class FakeLedger implements ProvenanceLedger {
        boolean valid = true, fail;
        int submissions;
        JsonObject last;
        public JsonObject submit(String kind, JsonObject data) throws Exception {
            submissions++;
            if (fail) throw new IOException("simulated connection lost after submission");
            last = new JsonObject(); last.addProperty("kind", kind); last.addProperty("txId", "test-tx");
            last.add("data", data.deepCopy()); return last;
        }
        public JsonObject checkOperation(JsonObject data) {
            JsonObject result = new JsonObject(); result.addProperty("valid", valid);
            JsonArray reasons = new JsonArray(); if (!valid) reasons.add("OPERATION_TYPE_NOT_ALLOWED");
            result.add("reasonCodes", reasons); return result;
        }
        public JsonObject read(String kind, String id) throws Exception {
            if (last == null) throw new IOException("NOT_FOUND");
            return last;
        }
        public JsonArray listBatch(String id) { JsonArray r = new JsonArray(); r.add(last); return r; }
        public void close() {}
    }
    static class FakeObjects implements ObjectStoragePort {
        byte[] bytes; int uploads; boolean fail;
        public StoredObject add(byte[] input) throws IOException {
            if (fail) throw new IOException("simulated IPFS unavailable");
            uploads++; bytes = input.clone(); return new StoredObject("fake-cid-unit-test-only", Hashing.sha256Hex(input), input.length);
        }
        public void pin(String cid) {}
        public byte[] cat(String cid) { return bytes; }
    }
    static class FakeStore implements ProvenanceStore {
        final Map<String, JsonObject> receipts = new HashMap<>();
        int projected; boolean failProject, failFinish;
        public boolean begin(String id, String kind, String recordId, String batchId, String payload) {
            if (receipts.containsKey(id)) {
                if (!receipts.get(id).get("payload").equals(JsonParser.parseString(payload))) throw new IllegalArgumentException("REQUEST_CONFLICT");
                return false;
            }
            JsonObject receipt = new JsonObject(); receipt.addProperty("status", "PENDING");
            receipt.add("payload", JsonParser.parseString(payload)); receipts.put(id, receipt);
            return true;
        }
        public void finish(String id, String status, String reasons, String txId) throws Exception {
            if (failFinish) throw new IOException("DB failed after ledger commit");
            JsonObject r = receipts.get(id); r.addProperty("status", status); r.add("reasonCodes", JsonParser.parseString(reasons));
            if (txId != null) r.addProperty("fabricTxId", txId);
        }
        public void project(JsonObject data) throws Exception {
            if (failProject) throw new IOException("simulated DB projection unavailable"); projected++;
        }
        public boolean acceptEvent(long block, String tx, String name, JsonObject data) { return true; }
        public JsonObject receipt(String id) { return receipts.get(id).deepCopy(); }
        public JsonObject snapshot(String id) { return new JsonObject(); }
        public long lastBlock() { return 0; }
        public void close() {}
    }
}
