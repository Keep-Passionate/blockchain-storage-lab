/** 文件功能：用明确标记的虚拟数据，验证四层业务、真实Fabric/IPFS/MySQL和错误分支。 */
package lab.blockchain.backend.provenance;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lab.blockchain.backend.adapter.IpfsHttpObjectStorage;
import lab.blockchain.backend.port.ObjectStoragePort;
import lab.blockchain.core.Hashing;
import lab.blockchain.core.StoredObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** 入口是命令行实验，不冒充移动端、定时监管任务或真实专家认证服务。 */
public final class SyntheticScenarioMain {
    private SyntheticScenarioMain() {}
    public static void main(String[] args) throws Exception {
        int sequence = args.length > 0 ? Integer.parseInt(args[0]) : 1;
        String batchId = ProvenanceRules.batchId("DEMOTEA", 2026, sequence);
        Path root = Path.of(FabricProvenanceLedger.env("LAB_ROOT", ".")).toAbsolutePath().normalize();
        Path output = root.resolve("runs/synthetic/" + batchId);
        Files.createDirectories(output);
        if (Files.exists(output.resolve("summary.json"))) {
            throw new IllegalStateException("已有该编号实验，请传入新的六位以内序号，不覆盖原记录: " + batchId);
        }
        String fixtureText = Files.readString(root.resolve("data/synthetic/scenario.json"))
                .replace("${RUN}", Integer.toString(sequence)).replace("${BATCH_ID}", batchId);
        JsonObject fixture = JsonParser.parseString(fixtureText).getAsJsonObject();
        fixture.getAsJsonObject("batch").addProperty("sequence", sequence);
        String password = System.getenv("MYSQL_PASSWORD");
        if (password == null || password.isBlank()) throw new IllegalArgumentException("缺少MYSQL_PASSWORD");
        JsonObject checks = new JsonObject();
        checks.addProperty("synthetic", true);
        checks.addProperty("batchId", batchId);
        var ipfs = new IpfsHttpObjectStorage(FabricProvenanceLedger.env("IPFS_API", "http://127.0.0.1:5001"));
        try (var store = new JdbcProvenanceStore(
                FabricProvenanceLedger.env("MYSQL_URL", "jdbc:mysql://127.0.0.1:3307/blockchain_storage?allowPublicKeyRetrieval=true&useSSL=false"),
                FabricProvenanceLedger.env("MYSQL_USER", "blockchain"), password);
             var producer = new FabricProvenanceLedger("org1");
             var reviewer = new FabricProvenanceLedger("org2")) {
            var service = new ProvenanceService(producer, ipfs, store);
            var reviewService = new ProvenanceService(reviewer, ipfs, store);
            commit(service.write(req(sequence, "plot"), "plot", fixture.getAsJsonObject("plot")));
            commit(service.write(req(sequence, "batch"), "batch", fixture.getAsJsonObject("batch")));
            commit(service.write(req(sequence, "operation"), "operation", fixture.getAsJsonObject("operation")));

            // 请求的业务规则不合格：返回原因码，数据库保留原始输入，链上无对应操作。
            JsonObject rejected = service.write(req(sequence, "rejected"), "operation", fixture.getAsJsonObject("rejectedOperation"));
            check(checks, "rejectedReceipt", rejected.get("status").getAsString().equals("REJECTED"));
            checks.add("reasonCodes", rejected.get("reasonCodes"));
            expectFailure(checks, "rejectedAbsentOnChain", "NOT_FOUND",
                    () -> producer.read("operation", "DEMO-BAD-" + sequence));
            expectFailure(checks, "duplicateBatchRejected", "RECORD_EXISTS",
                    () -> producer.submit("batch", fixture.getAsJsonObject("batch")));
            expectFailure(checks, "directBypassRejected", "OPERATION",
                    () -> producer.submit("operation", fixture.getAsJsonObject("rejectedOperation")));

            byte[] pass = Files.readAllBytes(root.resolve("data/synthetic/report-pass.txt"));
            byte[] fail = Files.readAllBytes(root.resolve("data/synthetic/report-fail.txt"));
            commit(service.inspection(req(sequence, "pass"), fixture.getAsJsonObject("passInspection"), pass));
            commit(service.inspection(req(sequence, "fail"), fixture.getAsJsonObject("failInspection"), fail));
            check(checks, "reportRoundTrip", Arrays.equals(pass, service.verifiedReport("DEMO-PASS-" + sequence)));
            check(checks, "failedInspectionOnChain", producer.read("inspection", "DEMO-FAIL-" + sequence)
                    .getAsJsonObject("data").get("conclusion").getAsString().equals("FAIL"));
            checks.addProperty("reportCid", producer.read("inspection", "DEMO-PASS-" + sequence)
                    .getAsJsonObject("data").get("reportCid").getAsString());

            // 故障注入只修改取回的内存字节，不修改IPFS真实对象或原始报告。
            ObjectStoragePort corruptedRead = new ObjectStoragePort() {
                public StoredObject add(byte[] bytes) { throw new UnsupportedOperationException(); }
                public void pin(String cid) { throw new UnsupportedOperationException(); }
                public byte[] cat(String cid) { return "tampered bytes".getBytes(StandardCharsets.UTF_8); }
            };
            expectFailure(checks, "tamperedReportRejected", "REPORT_HASH_MISMATCH",
                    () -> new ProvenanceService(producer, corruptedRead, store).verifiedReport("DEMO-PASS-" + sequence));

            JsonObject review = data("reviewId", "DEMO-REVIEW-" + sequence, batchId);
            review.addProperty("inspectionId", "DEMO-PASS-" + sequence);
            review.addProperty("decision", "APPROVE");
            review.addProperty("comment", "虚拟专家对演示报告的人工确认，不构成认证");
            expectFailure(checks, "producerCannotReview", "FORBIDDEN", () -> producer.submit("review", review));
            commit(reviewService.write(req(sequence, "review"), "review", review));
            JsonObject badReview = review.deepCopy();
            badReview.addProperty("reviewId", "DEMO-BAD-REVIEW-" + sequence);
            badReview.addProperty("inspectionId", "DEMO-FAIL-" + sequence);
            expectFailure(checks, "failedReportCannotBeApproved", "FAIL", () -> reviewer.submit("review", badReview));

            // 审计包固定本次审计输入，包含链上事实和链下拒绝回执，哈希不依赖未来数据库变化。
            JsonObject evidence = new JsonObject();
            JsonArray records = producer.listBatch(batchId);
            evidence.add("records", records);
            evidence.add("rejectedSubmission", rejected);
            byte[] auditBytes = evidence.toString().getBytes(StandardCharsets.UTF_8);
            Files.write(output.resolve("audit-input.json"), auditBytes);
            StoredObject auditObject = ipfs.add(auditBytes);
            ipfs.pin(auditObject.cid());
            JsonObject audit = data("auditId", "DEMO-AUDIT-" + sequence, batchId);
            JsonArray recordIds = new JsonArray();
            for (var item : records) {
                JsonObject r = item.getAsJsonObject();
                if (r.get("kind").getAsString().equals("operation") || r.get("kind").getAsString().equals("inspection")
                        || r.get("kind").getAsString().equals("review")) {
                    recordIds.add(r.get("kind").getAsString() + "/" + r.get("id").getAsString());
                }
            }
            audit.add("recordIds", recordIds);
            audit.addProperty("evidenceCid", auditObject.cid());
            audit.addProperty("evidenceSha256", Hashing.sha256Hex(auditBytes));
            audit.addProperty("ruleVersion", "trace-rules-v1");
            audit.addProperty("conclusion", "FLAGGED");
            audit.addProperty("comment", "限定本批次的虚拟审计：发现一条链下拒绝和一份FAIL报告；不是全网月度审计");
            commit(reviewService.write(req(sequence, "audit"), "audit", audit));
            check(checks, "auditEvidenceRoundTrip", Hashing.sha256Hex(ipfs.cat(auditObject.cid()))
                    .equals(audit.get("evidenceSha256").getAsString()));

            // 真实Fabric订阅协议重放已提交事件；链下inbox在事务中去重。
            int first = reviewer.replay(store, store.lastBlock(), 10000, 5);
            int duplicate = reviewer.replay(store, 0, 10000, 5);
            checks.addProperty("eventsAccepted", first);
            check(checks, "realEventsReceived", first >= 7);
            check(checks, "eventReplayDeduplicated", duplicate == 0);
            int alerts = simulateAlerts(batchId, reviewer, reviewService);
            checks.addProperty("simulatedAlerts", alerts);
            check(checks, "alertReconciliationIdempotent", simulateAlerts(batchId, reviewer, reviewService) == 0);
            reviewer.replay(store, store.lastBlock(), 10000, 5);
            JsonObject before = store.snapshot(batchId);
            JsonObject rebuilt = service.rebuild(batchId);
            check(checks, "projectionReplayIdempotent", before.equals(rebuilt));
            Files.writeString(output.resolve("snapshot.json"), new GsonBuilder().setPrettyPrinting().create().toJson(rebuilt));
            checks.addProperty("status", "PASS");
            Files.writeString(output.resolve("summary.json"), new GsonBuilder().setPrettyPrinting().create().toJson(checks));
            System.out.println(new GsonBuilder().setPrettyPrinting().create().toJson(checks));
        }
    }

    /** 模拟告警的本地处理与链上日志；确定性ID支持“收事件后崩溃”的补偿扫描。不会发送消息。 */
    private static int simulateAlerts(String batchId, ProvenanceLedger ledger, ProvenanceService service) throws Exception {
        JsonArray all = ledger.listBatch(batchId);
        var existing = new java.util.HashSet<String>();
        for (var item : all) if (item.getAsJsonObject().get("kind").getAsString().equals("alert"))
            existing.add(item.getAsJsonObject().get("id").getAsString());
        int added = 0;
        for (var item : all) {
            JsonObject record = item.getAsJsonObject();
            String kind = record.get("kind").getAsString();
            JsonObject body = record.getAsJsonObject("data");
            boolean alertable = kind.equals("inspection") && body.get("conclusion").getAsString().equals("FAIL")
                    || kind.equals("audit") && body.get("conclusion").getAsString().equals("FLAGGED");
            String alertId = "EV-" + record.get("txId").getAsString();
            if (!alertable || existing.contains(alertId)) continue;
            JsonObject alert = data("alertId", alertId, batchId);
            alert.addProperty("sourceKind", kind);
            alert.addProperty("sourceId", record.get("id").getAsString());
            alert.addProperty("deliveryStatus", "SIMULATED");
            alert.addProperty("recipient", "synthetic-user-and-regulator");
            alert.addProperty("comment", "演示告警，仅记录模拟处理，不发送短信、邮件或微信");
            commit(service.write(alertId, "alert", alert));
            added++;
        }
        return added;
    }

    private static String req(int n, String step) { return "DEMO-" + n + "-" + step; }
    private static JsonObject data(String key, String id, String batchId) {
        JsonObject data = new JsonObject();
        data.addProperty("schemaVersion", 1);
        data.addProperty("synthetic", true);
        data.addProperty(key, id);
        data.addProperty("batchId", batchId);
        return data;
    }
    private static void commit(JsonObject receipt) {
        if (!receipt.get("status").getAsString().equals("COMMITTED")) throw new AssertionError(receipt.toString());
    }
    private static void check(JsonObject result, String name, boolean passed) {
        result.addProperty(name, passed);
        if (!passed) throw new AssertionError(name);
    }
    @FunctionalInterface private interface CheckedAction { void run() throws Exception; }
    private static void expectFailure(JsonObject result, String name, String code, CheckedAction action) throws Exception {
        try { action.run(); }
        catch (Exception e) {
            StringBuilder messages = new StringBuilder();
            for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                messages.append(cause.getMessage()).append('\n');
                // Gateway 的外层消息可能只有 ABORTED；业务原因需要显式读取各背书节点的详情。
                if (cause instanceof org.hyperledger.fabric.client.GatewayException gatewayError) {
                    for (var detail : gatewayError.getDetails()) messages.append(detail.getMessage()).append('\n');
                }
                if (cause instanceof org.hyperledger.fabric.client.GatewayRuntimeException gatewayError) {
                    for (var detail : gatewayError.getDetails()) messages.append(detail.getMessage()).append('\n');
                }
            }
            if (!messages.toString().contains(code)) throw new AssertionError(name + ": unexpected failure: " + messages, e);
            result.addProperty(name, true);
            return;
        }
        throw new AssertionError(name + ": expected failure");
    }
}
