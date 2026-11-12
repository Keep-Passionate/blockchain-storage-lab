# Fabric 主链路

本项目当前直接使用 Hyperledger Fabric。Fabric 网络不复制到 Git 仓库，而是在服务器上使用官方 `fabric-samples/test-network` 管理，以免把证书、密钥和大量 Docker 配置混入实验代码。

当前学习网络采用官方 test-network 的两组织、Peer、Raft Orderer 和单 Channel 结构。它用于学习和验证，不作为生产网络模板。

服务器流程：

```bash
export FABRIC_SAMPLES_REF='<已审核的 fabric-samples 提交号>'
./scripts/install-fabric.sh
./scripts/fabric-network.sh up
./scripts/fabric-network.sh deploy
```

停止网络：

```bash
./scripts/fabric-network.sh down
```

`deploy` 的链码路径指向仓库根目录下的 `chaincode-go`，其中同时包含 `go.mod` 和 `package main`。

Java 通过 `FabricGatewayLedger` 使用 Org1 用户身份连接 Peer 的 Gateway。证书、私钥、TLS CA 和 connection profile 只保留在服务器运行目录，不进入 Git。
