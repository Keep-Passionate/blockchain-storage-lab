# Java backend: IPFS + Fabric

这里是当前主链路的 Java 业务层，直接调用 Kubo HTTP API 和官方 Fabric Gateway Java client。

当前调用链是：

```text
HTTP Controller（后续加入） -> TraceApplicationService
                             -> IpfsHttpObjectStorage -> Kubo /api/v0/add, cat, pin/add
                             -> FabricGatewayLedger -> Gateway -> Peer / Orderer / Channel / Chaincode
                             -> MySQL（预留索引）
                             -> Redis（预留缓存）
```

`TraceApplicationService` 先上传文件并 pin，拿到 CID 后再提交 Fabric。查询方向相反：先从 Fabric 得到 CID，再从 IPFS `cat` 取回文件。

`FabricGatewayConfig` 只接收服务器证书路径；证书、私钥和 connection profile 不进入 Git。

服务器完成 Fabric/IPFS 启动后，可用 `FabricIpfsDemoMain` 做一次真实冒烟测试：

```bash
export FABRIC_TLS_CERT_PATH=/path/to/peer0.org1.example.com/tls/ca.crt
export FABRIC_IDENTITY_CERT_PATH=/path/to/User1@org1.example.com/msp/signcerts/cert.pem
export FABRIC_PRIVATE_KEY_PATH=/path/to/User1@org1.example.com/msp/keystore/<key>_sk
mvn -q -pl backend-java exec:java \
  -Dexec.mainClass=lab.blockchain.backend.FabricIpfsDemoMain
```

程序会输出真实 IPFS CID、Fabric 查询到的事件数量和文件往返校验结果。
