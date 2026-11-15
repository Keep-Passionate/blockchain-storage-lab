/** 文件功能：查询已有批次、比较链上事实与数据库投影，并按链上摘要校验 IPFS 原件。 */
package lab.blockchain.backend.provenance;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lab.blockchain.backend.adapter.IpfsHttpObjectStorage;
import lab.blockchain.backend.port.ObjectStoragePort;
import lab.blockchain.core.Hashing;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class ProvenanceQueryMain {
    private static final String[] KINDS = {"plot", "batch", "operation", "inspection", "review", "audit", "alert"};
    // 只展示明确列出的教学字段；原始请求、证书身份、操作者声明和自由评论均不打印。
    private static final Map<String, String> VISIBLE_FIELDS = Map.of(
            "plot", "plotId regionCode crop variety growthStage altitudeM",
            "batch", "batchId regionCode year sequence plotId startedAt",
            "operation", "operationId batchId plotId operationType performedAt ruleVersion",
            "inspection", "inspectionId batchId reportCid reportSha256 conclusion testedAt",
            "review", "reviewId batchId inspectionId decision",
            "audit", "auditId batchId recordIds evidenceCid evidenceSha256 conclusion ruleVersion",
            "alert", "alertId batchId sourceKind sourceId deliveryStatus");

    private ProvenanceQueryMain() { }

    /** --help 不连接服务；正常查询不会提交交易、上传 IPFS 或修改数据库。 */
    public static void main(String[] args) {
        if (args.length == 0 || (args.length == 1 && (args[0].equals("--help") || args[0].equals("-h")))) {
            System.out.println("用法: bash scripts/query-batch.sh <已有批次号> [--rebuild]");
            System.out.println("默认只读链和数据库，并校验 IPFS 原件；--rebuild 从链补齐该批次投影，不修改提交证据。");
            return;
        }
        if (args.length > 2 || !args[0].matches("[A-Z0-9]{2,12}-[0-9]{4}-[0-9]{6}")
                || (args.length == 2 && !args[1].equals("--rebuild"))) {
            throw new IllegalArgumentException("INVALID_ARGUMENTS: 使用 --help 查看用法");
        }
        String password = System.getenv("MYSQL_PASSWORD");
        if (password == null || password.isBlank()) throw new IllegalStateException("MYSQL_PASSWORD_NOT_CONFIGURED");
        try (var ledger = new FabricProvenanceLedger("org1");
             var store = new JdbcProvenanceStore(FabricProvenanceLedger.env("MYSQL_URL",
                     "jdbc:mysql://127.0.0.1:3307/blockchain_storage?allowPublicKeyRetrieval=true&useSSL=false"),
                     FabricProvenanceLedger.env("MYSQL_USER", "blockchain"), password)) {
            var objects = new IpfsHttpObjectStorage(FabricProvenanceLedger.env("IPFS_API", "http://127.0.0.1:5001"));
            JsonObject result = query(args[0], args.length == 2, ledger, store, objects);
            String json = new GsonBuilder().setPrettyPrinting().create().toJson(result);
            Path directory = saveSummary(args[0], json);
            System.out.println("Fabric 是事实来源；databaseSnapshot 是脱敏查询副本；objectChecks 直接使用链上的 CID 和 SHA-256。");
            System.out.println(json);
            System.out.println("查询摘要: runs/query/" + directory.getFileName() + "/summary.json");
            if (!result.get("checksPassed").getAsBoolean()) {
                throw new IllegalStateException("QUERY_CHECKS_FAILED");
            }
        } catch (Exception failure) {
            // 驱动/网关原始异常可能带连接参数或证书主体，不把它们交给 Maven 打印。
            throw new IllegalStateException("QUERY_FAILED: 检查上述摘要；若尚无摘要，请确认环境配置、服务状态及批次号");
        }
    }

    /** 对已有实体按依赖顺序补投影；没有 DELETE、回执更新或链上写入。 */
    static JsonObject query(String batchId, boolean rebuild, ProvenanceLedger ledger,
                            ProvenanceStore store, ObjectStoragePort objects) throws Exception {
        JsonArray chain = ledger.listBatch(batchId);
        if (rebuild) for (JsonElement record : chain) store.project(record.getAsJsonObject());
        JsonObject snapshot = store.snapshot(batchId);
        Map<String, JsonObject> chainRecords = new LinkedHashMap<>();
        Map<String, JsonObject> databaseRecords = new LinkedHashMap<>();
        for (JsonElement record : chain) putRecord(chainRecords, record.getAsJsonObject());
        JsonObject visibleSnapshot = new JsonObject();
        JsonObject counts = new JsonObject();
        for (String kind : KINDS) {
            JsonArray records = new JsonArray();
            if (kind.equals("plot") || kind.equals("batch")) {
                JsonElement record = snapshot.get(kind);
                if (record != null && !record.isJsonNull()) records.add(record);
            } else {
                records = snapshot.getAsJsonArray(kind + "s");
            }
            JsonArray visible = new JsonArray();
            for (JsonElement record : records) {
                JsonObject envelope = record.getAsJsonObject();
                putRecord(databaseRecords, envelope);
                visible.add(select(envelope.getAsJsonObject("data"), VISIBLE_FIELDS.get(kind)));
            }
            visibleSnapshot.add(kind + "s", visible);
            JsonObject count = new JsonObject();
            int chainCount = 0;
            for (JsonObject record : chainRecords.values()) if (record.get("kind").getAsString().equals(kind)) chainCount++;
            count.addProperty("ledger", chainCount);
            count.addProperty("database", records.size());
            counts.add(kind, count);
        }
        JsonArray receipts = new JsonArray();
        for (JsonElement receipt : snapshot.getAsJsonArray("submissions")) {
            receipts.add(select(receipt.getAsJsonObject(), "requestId kind recordId status reasonCodes payloadSha256 fabricTxId"));
        }
        visibleSnapshot.add("submissions", receipts);

        // 比较完整 envelope，不因为展示时隐藏了身份就忽略身份字段的篡改。
        boolean projectionMatches = chainRecords.equals(databaseRecords);
        JsonArray objectChecks = new JsonArray();
        boolean objectsMatch = true;
        for (JsonObject record : chainRecords.values()) {
            String kind = record.get("kind").getAsString();
            if (!kind.equals("inspection") && !kind.equals("audit")) continue;
            JsonObject check = checkObject(record, objects);
            objectChecks.add(check);
            objectsMatch &= check.get("status").getAsString().equals("MATCH");
        }
        JsonObject result = new JsonObject();
        result.addProperty("batchId", batchId);
        result.addProperty("queriedAt", Instant.now().toString());
        result.addProperty("mode", rebuild ? "REBUILD_PROJECTIONS" : "READ_ONLY");
        result.addProperty("checksPassed", projectionMatches && objectsMatch);
        result.addProperty("projectionMatchesLedger", projectionMatches);
        result.addProperty("objectHashesMatch", objectsMatch);
        result.add("counts", counts);
        result.add("databaseSnapshot", visibleSnapshot);
        result.add("objectChecks", objectChecks);
        return result;
    }

    /** 文件不落地、不打印内容；摘要验证失败和 IPFS 不可达分别记录。 */
    private static JsonObject checkObject(JsonObject record, ObjectStoragePort objects) {
        boolean inspection = record.get("kind").getAsString().equals("inspection");
        JsonObject data = record.getAsJsonObject("data");
        String cid = data.get(inspection ? "reportCid" : "evidenceCid").getAsString();
        String expected = data.get(inspection ? "reportSha256" : "evidenceSha256").getAsString();
        JsonObject result = select(record, "kind id");
        result.addProperty("cid", cid);
        result.addProperty("expectedSha256", expected);
        try {
            byte[] bytes = objects.cat(cid);
            String actual = Hashing.sha256Hex(bytes);
            result.addProperty("bytes", bytes.length);
            result.addProperty("actualSha256", actual);
            result.addProperty("status", expected.equals(actual) ? "MATCH" : "HASH_MISMATCH");
        } catch (Exception failure) {
            result.addProperty("status", "UNAVAILABLE");
        }
        return result;
    }

    private static JsonObject select(JsonObject source, String fields) {
        JsonObject result = new JsonObject();
        for (String field : fields.split(" ")) if (source.has(field)) result.add(field, source.get(field).deepCopy());
        return result;
    }

    private static void putRecord(Map<String, JsonObject> records, JsonObject record) {
        String key = record.get("kind").getAsString() + "/" + record.get("id").getAsString();
        if (records.putIfAbsent(key, record) != null) throw new IllegalStateException("DUPLICATE_RECORD_IN_QUERY");
    }

    /** 每次查询保存独立脱敏摘要，不覆盖 runs/synthetic 中的原始实验记录。 */
    private static Path saveSummary(String batchId, String json) throws Exception {
        String timestamp = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss.SSS'Z'")
                .withZone(ZoneOffset.UTC).format(Instant.now());
        Path root = Path.of(FabricProvenanceLedger.env("LAB_ROOT", ".")).toAbsolutePath().normalize();
        Path parent = root.resolve("runs/query");
        Files.createDirectories(parent);
        Path directory = Files.createDirectory(parent.resolve(batchId + "-" + timestamp + "-" + UUID.randomUUID()));
        Files.writeString(directory.resolve("summary.json"), json + System.lineSeparator(),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        return directory;
    }
}
