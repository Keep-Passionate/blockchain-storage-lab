/** 文件功能：通过官方 Fabric Gateway Java client 提交和查询溯源事件。 */
package lab.blockchain.backend.adapter;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.Gson;
import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.TlsChannelCredentials;
import lab.blockchain.backend.port.LedgerPort;
import lab.blockchain.core.Stage;
import lab.blockchain.core.TraceEvent;
import org.hyperledger.fabric.client.Contract;
import org.hyperledger.fabric.client.Gateway;
import org.hyperledger.fabric.client.Hash;
import org.hyperledger.fabric.client.identity.Identities;
import org.hyperledger.fabric.client.identity.Signer;
import org.hyperledger.fabric.client.identity.Signers;
import org.hyperledger.fabric.client.identity.X509Identity;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

/**
 * Fabric 适配器只保存链上小型事实：CID、阶段、时间、操作者和摘要。
 * 它不会上传文件，也不会访问 MySQL、Redis 或 IPFS。
 */
public final class FabricGatewayLedger implements LedgerPort, AutoCloseable {
    private static final Gson JSON = new Gson();

    private final ManagedChannel channel;
    private final Gateway gateway;
    private final Contract contract;

    private FabricGatewayLedger(ManagedChannel channel, Gateway gateway, Contract contract) {
        this.channel = channel;
        this.gateway = gateway;
        this.contract = contract;
    }

    /** 根据服务器上的 Fabric test-network 证书建立 Gateway 连接。 */
    public static FabricGatewayLedger connect(FabricGatewayConfig config) throws IOException {
        ManagedChannel channel = null;
        try {
            var credentials = TlsChannelCredentials.newBuilder()
                    .trustManager(config.tlsCertificate().toFile())
                    .build();
            channel = Grpc.newChannelBuilder(config.peerEndpoint(), credentials)
                    .overrideAuthority(config.peerHostOverride())
                    .build();

            X509Certificate certificate;
            PrivateKey privateKey;
            try (Reader certReader = Files.newBufferedReader(config.identityCertificate())) {
                certificate = Identities.readX509Certificate(certReader);
            }
            try (Reader keyReader = Files.newBufferedReader(config.privateKey())) {
                privateKey = Identities.readPrivateKey(keyReader);
            }

            var identity = new X509Identity(config.mspId(), certificate);
            Signer signer = Signers.newPrivateKeySigner(privateKey);
            Gateway gateway = Gateway.newInstance()
                    .identity(identity)
                    .signer(signer)
                    .hash(Hash.SHA256)
                    .connection(channel)
                    .connect();
            Contract contract = gateway.getNetwork(config.channelName()).getContract(config.chaincodeName());
            return new FabricGatewayLedger(channel, gateway, contract);
        } catch (Exception e) {
            if (channel != null) {
                channel.shutdownNow();
            }
            throw new IOException("无法连接 Fabric Gateway", e);
        }
    }

    @Override
    public void create(TraceEvent event) throws IOException {
        try {
            contract.submitTransaction("CreateTrace", toJson(event));
        } catch (Exception e) {
            throw new IOException("Fabric CreateTrace 提交失败", e);
        }
    }

    @Override
    public void append(TraceEvent event) throws IOException {
        try {
            contract.submitTransaction("AppendEvent", toJson(event));
        } catch (Exception e) {
            throw new IOException("Fabric AppendEvent 提交失败", e);
        }
    }

    @Override
    public List<TraceEvent> query(String traceId) throws IOException {
        try {
            byte[] payload = contract.evaluateTransaction("QueryTrace", traceId);
            JsonArray events = JsonParser.parseString(new String(payload, StandardCharsets.UTF_8))
                    .getAsJsonArray();
            List<TraceEvent> result = new ArrayList<>();
            for (JsonElement element : events) {
                result.add(fromJson(element.getAsJsonObject()));
            }
            return List.copyOf(result);
        } catch (Exception e) {
            throw new IOException("Fabric QueryTrace 查询失败", e);
        }
    }

    @Override
    public void close() {
        gateway.close();
        channel.shutdownNow();
    }

    private static String toJson(TraceEvent event) {
        JsonObject object = new JsonObject();
        object.addProperty("traceId", event.traceId());
        object.addProperty("objectId", event.objectId());
        object.addProperty("stage", event.stage().name());
        object.addProperty("operatorId", event.operatorId());
        object.addProperty("eventTime", event.eventTime().toString());
        object.addProperty("contentCid", event.contentCid());
        object.addProperty("metadataHash", event.metadataHash());
        object.addProperty("description", event.description());
        return JSON.toJson(object);
    }

    private static TraceEvent fromJson(JsonObject object) {
        return new TraceEvent(
                required(object, "traceId"),
                required(object, "objectId"),
                Stage.valueOf(required(object, "stage")),
                required(object, "operatorId"),
                java.time.Instant.parse(required(object, "eventTime")),
                required(object, "contentCid"),
                required(object, "metadataHash"),
                required(object, "description"));
    }

    private static String required(JsonObject object, String name) {
        JsonPrimitive value = object.getAsJsonPrimitive(name);
        if (value == null || value.getAsString().isBlank()) {
            throw new IllegalArgumentException("Fabric event field is missing: " + name);
        }
        return value.getAsString();
    }
}
