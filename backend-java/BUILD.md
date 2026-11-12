# backend-java 构建和运行

本模块直接编译真实的 IPFS 和 Fabric Gateway 适配器。当前暂不加入 Spring Boot Controller，让每个组件的责任可以单独阅读；后续加 Web 层时只需要把 Controller 接到 `TraceApplicationService`。

## 编译

```bash
mvn -q -pl backend-java -am test
```

## 服务器冒烟测试

服务器启动 Kubo 和 Fabric test-network、部署 `trace` 链码后，设置证书路径：

```bash
export FABRIC_TLS_CERT_PATH=/path/to/tls/ca.crt
export FABRIC_IDENTITY_CERT_PATH=/path/to/signcerts/cert.pem
export FABRIC_PRIVATE_KEY_PATH=/path/to/keystore/key_sk
mvn -q -pl backend-java exec:java \
  -Dexec.mainClass=lab.blockchain.backend.FabricIpfsDemoMain
```

代码顺序是：`IpfsHttpObjectStorage.add` → `pin` → `FabricGatewayLedger.create` → `QueryTrace` → `cat`。
