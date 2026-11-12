/** 文件功能：连接真实 Kubo 和 Fabric Gateway，执行一次最小链上链下冒烟测试。 */
package lab.blockchain.backend;

import lab.blockchain.backend.adapter.FabricGatewayConfig;
import lab.blockchain.backend.adapter.FabricGatewayLedger;
import lab.blockchain.backend.adapter.IpfsHttpObjectStorage;
import lab.blockchain.backend.dto.CreateTraceCommand;
import lab.blockchain.backend.service.TraceApplicationService;
import lab.blockchain.core.Stage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;

/**
 * 该程序只在服务器 Fabric/IPFS 已启动且证书环境变量已配置时运行。
 * 它让“真实组件是否连通”有一个可读、可重复的入口。
 */
public final class FabricIpfsDemoMain {
    private FabricIpfsDemoMain() {}

    public static void main(String[] args) throws Exception {
        String traceId = args.length > 0 ? args[0] : "TRACE-DEMO-001";
        byte[] file = args.length > 1
                ? Files.readAllBytes(Path.of(args[1]))
                : "server-side IPFS + Fabric smoke test".getBytes(StandardCharsets.UTF_8);

        FabricGatewayConfig fabricConfig = new FabricGatewayConfig(
                envOrDefault("FABRIC_MSP_ID", "Org1MSP"),
                envOrDefault("FABRIC_CHANNEL", "mychannel"),
                envOrDefault("FABRIC_CHAINCODE", "trace"),
                envOrDefault("FABRIC_PEER_ENDPOINT", "localhost:7051"),
                envOrDefault("FABRIC_PEER_HOST_OVERRIDE", "peer0.org1.example.com"),
                requiredPath("FABRIC_TLS_CERT_PATH"),
                requiredPath("FABRIC_IDENTITY_CERT_PATH"),
                requiredPath("FABRIC_PRIVATE_KEY_PATH"));

        IpfsHttpObjectStorage ipfs = new IpfsHttpObjectStorage(
                envOrDefault("IPFS_API", "http://127.0.0.1:5001"));
        try (FabricGatewayLedger fabric = FabricGatewayLedger.connect(fabricConfig)) {
            TraceApplicationService service = new TraceApplicationService(ipfs, fabric);
            var result = service.create(new CreateTraceCommand(
                    traceId, "OBJECT-DEMO-001", Stage.PROCESSING, "student",
                    "真实 IPFS + Fabric 冒烟记录", file));
            var queried = service.query(traceId);
            boolean roundTrip = java.util.Arrays.equals(file, service.fetch(queried.get(0)));

            System.out.println("cid=" + result.event().contentCid());
            System.out.println("fabricEvents=" + queried.size());
            System.out.println("ipfsRoundTripVerified=" + roundTrip);
        }
    }

    private static Path requiredPath(String name) {
        return Path.of(env(name));
    }

    private static String env(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("缺少环境变量: " + name);
        }
        return value;
    }

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
