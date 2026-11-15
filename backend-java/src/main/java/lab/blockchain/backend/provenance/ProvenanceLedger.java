/** 文件功能：定义四层业务需要的账本接口，让业务流程与 Fabric SDK 分开阅读。 */
package lab.blockchain.backend.provenance;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public interface ProvenanceLedger extends AutoCloseable {
    /** 返回成功提交后的完整链上记录，而非尚未确认的背书结果。 */
    JsonObject submit(String kind, JsonObject data) throws Exception;
    JsonObject checkOperation(JsonObject data) throws Exception;
    JsonObject read(String kind, String id) throws Exception;
    JsonArray listBatch(String batchId) throws Exception;
    @Override void close() throws Exception;
}
