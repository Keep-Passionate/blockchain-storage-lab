/** 文件功能：维护入口格式约束和演示批次编号；关系与操作规则以链码为准。 */
package lab.blockchain.backend.provenance;

import com.google.gson.JsonObject;
import java.util.Locale;
import java.util.Map;

public final class ProvenanceRules {
    private ProvenanceRules() {}
    public static final Map<String, String> ID_FIELDS = Map.of(
            "plot", "plotId", "batch", "batchId", "operation", "operationId",
            "inspection", "inspectionId", "review", "reviewId", "audit", "auditId", "alert", "alertId");

    /** 同一区域、年份与序号得到同一个编号；唯一性最终由链上键保障。 */
    public static String batchId(String regionCode, int year, int sequence) {
        if (regionCode == null || !regionCode.matches("[A-Z0-9]{2,12}")
                || year < 2000 || year > 2100 || sequence < 1 || sequence > 999999) {
            throw new IllegalArgumentException("INVALID_BATCH_ID");
        }
        return String.format(Locale.ROOT, "%s-%04d-%06d", regionCode, year, sequence);
    }

    public static String id(String kind, JsonObject data) {
        String field = ID_FIELDS.get(kind);
        if (field == null) throw new IllegalArgumentException("UNKNOWN_KIND");
        String result = text(data, field);
        if (!result.matches("[A-Za-z0-9_.-]{1,128}")) throw new IllegalArgumentException("INVALID_ID");
        return result;
    }

    public static String text(JsonObject data, String key) {
        if (!data.has(key) || !data.get(key).isJsonPrimitive()
                || !data.getAsJsonPrimitive(key).isString() || data.get(key).getAsString().isBlank()) {
            throw new IllegalArgumentException("MISSING_FIELD: " + key);
        }
        return data.get(key).getAsString();
    }

    /** 当前数据库/链码是合成实验命名空间，不能混入真实生产数据。 */
    public static void requireSynthetic(JsonObject data) {
        if (!data.has("synthetic") || !data.getAsJsonPrimitive("synthetic").isBoolean()
                || !data.get("synthetic").getAsBoolean()
                || !data.has("schemaVersion") || !data.get("schemaVersion").toString().equals("1")) {
            throw new IllegalArgumentException("SYNTHETIC_SCHEMA_REQUIRED");
        }
    }
}
