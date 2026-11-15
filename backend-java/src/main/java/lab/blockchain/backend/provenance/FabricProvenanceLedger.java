/** 文件功能：连接 Fabric 的 ProvenanceContract，等待真实提交并重放合约事件。 */
package lab.blockchain.backend.provenance;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.TlsChannelCredentials;
import org.hyperledger.fabric.client.Contract;
import org.hyperledger.fabric.client.Gateway;
import org.hyperledger.fabric.client.Hash;
import org.hyperledger.fabric.client.Network;
import org.hyperledger.fabric.client.identity.Identities;
import org.hyperledger.fabric.client.identity.Signers;
import org.hyperledger.fabric.client.identity.X509Identity;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class FabricProvenanceLedger implements ProvenanceLedger {
    private static final Map<String, String> METHODS = Map.of("plot", "PutPlot", "batch", "PutBatch",
            "operation", "PutOperation", "inspection", "PutInspection", "review", "PutReview",
            "audit", "PutAudit", "alert", "PutAlert");
    private final ManagedChannel channel;
    private final Gateway gateway;
    private final Network network;
    private final Contract contract;
    private final String chaincodeName;

    /** 实验网络用 Org1 录入、Org2 审核，使用两个真实 X.509 身份；不能只传 operatorId 代替授权。 */
    public FabricProvenanceLedger(String org) throws Exception {
        if (!org.equals("org1") && !org.equals("org2")) throw new IllegalArgumentException("Unknown org");
        Path samples = Path.of(env("FABRIC_SAMPLES_DIR", System.getProperty("user.home") + "/xc/fabric-samples"));
        String domain = org + ".example.com";
        Path root = samples.resolve("test-network/organizations/peerOrganizations/" + domain);
        Path identity = root.resolve("users/User1@" + domain + "/msp");
        Path key;
        try (var files = Files.list(identity.resolve("keystore"))) {
            var keys = files.filter(Files::isRegularFile).toList();
            if (keys.size() != 1) throw new IllegalStateException("Expected one enrollment key for " + org);
            key = keys.get(0);
        }
        var tls = TlsChannelCredentials.newBuilder()
                .trustManager(root.resolve("peers/peer0." + domain + "/tls/ca.crt").toFile()).build();
        channel = Grpc.newChannelBuilder(env("FABRIC_" + org.toUpperCase() + "_ENDPOINT",
                org.equals("org1") ? "localhost:7051" : "localhost:9051"), tls)
                .overrideAuthority("peer0." + domain).build();
        try (var certReader = Files.newBufferedReader(identity.resolve("signcerts/cert.pem"));
             var keyReader = Files.newBufferedReader(key)) {
            gateway = Gateway.newInstance()
                    .identity(new X509Identity(org.equals("org1") ? "Org1MSP" : "Org2MSP",
                            Identities.readX509Certificate(certReader)))
                    .signer(Signers.newPrivateKeySigner(Identities.readPrivateKey(keyReader)))
                    .hash(Hash.SHA256).connection(channel)
                    .evaluateOptions(o -> o.withDeadlineAfter(15, TimeUnit.SECONDS))
                    .endorseOptions(o -> o.withDeadlineAfter(30, TimeUnit.SECONDS))
                    .submitOptions(o -> o.withDeadlineAfter(15, TimeUnit.SECONDS))
                    .commitStatusOptions(o -> o.withDeadlineAfter(60, TimeUnit.SECONDS)).connect();
        } catch (Exception e) {
            channel.shutdownNow();
            throw e;
        }
        chaincodeName = env("PROVENANCE_CHAINCODE", "provenance");
        network = gateway.getNetwork(env("FABRIC_CHANNEL", "mychannel"));
        contract = network.getContract(chaincodeName, "ProvenanceContract");
    }

    @Override public JsonObject submit(String kind, JsonObject data) throws Exception {
        String method = METHODS.get(kind);
        if (method == null) throw new IllegalArgumentException("UNKNOWN_KIND");
        // submitTransaction 等待 VALID 提交；不能把仅完成模拟执行的 proposal 当作上链成功。
        return object(contract.submitTransaction(method, data.toString()));
    }
    @Override public JsonObject checkOperation(JsonObject data) throws Exception {
        return object(contract.evaluateTransaction("CheckOperation", data.toString()));
    }
    @Override public JsonObject read(String kind, String id) throws Exception {
        return object(contract.evaluateTransaction("ReadRecord", kind, id));
    }
    @Override public JsonArray listBatch(String batchId) throws Exception {
        return JsonParser.parseString(new String(contract.evaluateTransaction("ListBatch", batchId),
                StandardCharsets.UTF_8)).getAsJsonArray();
    }

    /** 从最近已处理块开始（含该块）恢复；同块重放由 MySQL 唯一键去重。 */
    public int replay(ProvenanceStore store, long startBlock, int maxEvents, int seconds) throws Exception {
        int accepted = 0;
        int seen = 0;
        try (var events = network.newChaincodeEventsRequest(chaincodeName).startBlock(startBlock).build()
                .getEvents(o -> o.withDeadlineAfter(seconds, TimeUnit.SECONDS))) {
            while (seen < maxEvents && events.hasNext()) {
                var event = events.next();
                seen++;
                // 同一二进制兼容保留旧教学合约；只解析四层合约的 envelope 事件。
                if (!java.util.Set.of("RecordCommitted", "InspectionFailed", "AuditFlagged")
                        .contains(event.getEventName())) continue;
                if (store.acceptEvent(event.getBlockNumber(), event.getTransactionId(), event.getEventName(),
                        object(event.getPayload()))) accepted++;
            }
        } catch (RuntimeException e) {
            // 有限时实验订阅在无新事件时正常超时；权限/连接等其它异常不能吞掉。
            if (Status.fromThrowable(e).getCode() != Status.Code.DEADLINE_EXCEEDED) throw e;
        }
        return accepted;
    }

    private static JsonObject object(byte[] bytes) {
        return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
    }
    public static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
    @Override public void close() {
        gateway.close();
        channel.shutdownNow();
    }
}
