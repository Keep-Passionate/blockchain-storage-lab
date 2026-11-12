/** 文件功能：集中保存 Fabric Gateway 连接所需的网络和证书路径。 */
package lab.blockchain.backend.adapter;

import java.nio.file.Path;

/**
 * Fabric test-network 的连接参数。
 * 证书和私钥只放服务器运行目录，不进入 Git。
 */
public record FabricGatewayConfig(
        String mspId,
        String channelName,
        String chaincodeName,
        String peerEndpoint,
        String peerHostOverride,
        Path tlsCertificate,
        Path identityCertificate,
        Path privateKey
) {}
