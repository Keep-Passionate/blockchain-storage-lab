# Blockchain Storage Lab

这是一个面向学习和后续研究的链上链下存储实验仓库。当前主链路直接使用老师偏好的 **IPFS + Hyperledger Fabric**：IPFS 保存文件实体，Fabric 保存 CID 和关键业务事实，MySQL/Redis 只承担可重建的索引与缓存。

## 当前主架构

```mermaid
flowchart LR
    User[用户/未来前端] --> App[Java 业务层]
    App --> S[TraceApplicationService]
    S -->|1. add| IPFS[Kubo IPFS]
    IPFS -->|CID| S
    S -->|2. pin| IPFS
    S -->|3. CreateTrace(CID+事实)| Gateway[Fabric Gateway]
    Gateway --> Peer1[Org1 Peer]
    Gateway --> Peer2[Org2 Peer]
    Peer1 --> Orderer[Raft Orderer]
    Peer2 --> Orderer
    Orderer --> Peer1
    Orderer --> Peer2
    Peer1 --> WorldState[Channel World State]
    S -.预留索引.-> MySQL[MySQL]
    S -.预留缓存.-> Redis[Redis]
```

## 一次写入的数据流

可缩放的独立架构图见 [`docs/architecture-data-flow.svg`](docs/architecture-data-flow.svg)；文字版 Mermaid 图保留在本 README，便于继续修改。

1. Java 收到文件和溯源字段。
2. `IpfsHttpObjectStorage` 调用 Kubo `/api/v0/add`，得到真实 IPFS CID。
3. Java 调用 `/api/v0/pin/add`，保证当前节点保留对象。
4. `TraceApplicationService` 组成 `TraceEvent`，其中只包含 CID、阶段、操作者、时间和摘要。
5. `FabricGatewayLedger` 调用 `CreateTrace`。
6. Fabric Peer 背书，Orderer 排序打包，Peer 提交区块并更新 World State。

查询方向相反：Fabric `QueryTrace(traceId)` 返回事件和 CID，Java 再调用 IPFS `/api/v0/cat` 取文件，并重新计算 SHA-256 校验内容。

链码永远不读取 IPFS，也不保存文件字节。这样链码保持小而容易审计，文件存储和业务共识各自负责一件事。

## 代码目录

```text
core-java/                  离线教学对照：区块、哈希、Merkle、文件完整性
backend-java/               当前主链路：IPFS HTTP 适配器 + Fabric Gateway 适配器
chaincode-go/               当前 Fabric 链码：CreateTrace/AppendEvent/QueryTrace
infrastructure/             服务器端 IPFS、MySQL、Redis，以及 Fabric 说明
scripts/                    服务器部署、Fabric 安装和网络启动脚本
docs/                       学习路线、架构、数据流和第一篇论文的后续扩展
```

`core-java` 保留用于离线理解数据结构和写单元测试；运行系统的主路径已经是 `backend-java -> IPFS + Fabric`，不再用自有链替代 Fabric。

## 服务器部署边界

代码工作区位于 `D:/project/blockchain-storage-lab`，服务器工作区约定为 `xc/blockchain-storage-lab`。Docker、Kubo IPFS、Fabric、MySQL 和 Redis 只在服务器运行，本地不启动这些服务。

本次实验服务器的已验证工具链是：Ubuntu 18.04.6、Java 17.0.7、Docker Engine 29.9.3（API 1.56）、Docker Compose 5.7.1、Go 1.27.4、Maven 3.10.0、Kubo 0.43.1、Fabric 2.5.16 和 Fabric CA 1.5.17。服务器原来的 Docker 20.10.21（API 1.41）和 Compose 1.17.1 会与 Fabric 2.5.16 链码构建冲突，因此新版本只安装在 `~/xc/tools` 或 `/usr/local/bin`，旧可执行文件保留为 `.bak`；不要对整台 Ubuntu 做发行版升级，以免影响 `xc` 下其他进程。

Ubuntu 18.04 无法直接运行官方 Fabric 主机二进制所需的新 glibc，所以服务器上的 `peer`、`configtxgen` 等命令通过 `hyperledger/fabric-tools:2.5.16` 容器包装运行，Fabric CA 客户端通过 `hyperledger/fabric-ca:1.5.17` 包装运行。`fabric-samples` 是服务器运行时目录，不复制进本仓库；证书、私钥、通道产物和链码构建目录也不提交。

Fabric test-network 使用官方 samples 脚本管理，不把证书、私钥和 Docker 网络复制进 Git。先设置已审核的 `FABRIC_SAMPLES_REF`，再运行：

```bash
cd ~/xc/blockchain-storage-lab
export PATH="$HOME/xc/tools/go/bin:$HOME/xc/fabric-samples/bin:$PATH"
sg docker -c 'PATH="$HOME/xc/tools/go/bin:$HOME/xc/fabric-samples/bin:$PATH" ./scripts/fabric-network.sh up'
sg docker -c 'PATH="$HOME/xc/tools/go/bin:$HOME/xc/fabric-samples/bin:$PATH" GOPROXY=https://goproxy.cn,direct ./scripts/fabric-network.sh deploy'
```

网络关闭：

```bash
sg docker -c 'PATH="$HOME/xc/tools/go/bin:$HOME/xc/fabric-samples/bin:$PATH" ./scripts/fabric-network.sh down'
```

服务器第一次部署链码前必须保留 `chaincode-go/go.sum`。如果服务器网络访问 `proxy.golang.org` 超时，可临时使用 `GOPROXY=https://goproxy.cn,direct` 生成或下载依赖；`go mod tidy` 只更新 Go 依赖锁定文件，不应把 `vendor/`、证书或 Fabric 生成目录提交到 Git。

Java 端到端冒烟测试先构建 Maven 反应堆，再设置 Fabric 用户证书并运行主类：

```bash
mvn -q -pl backend-java -am install
export FABRIC_TLS_CERT_PATH="$HOME/xc/fabric-samples/test-network/organizations/peerOrganizations/org1.example.com/peers/peer0.org1.example.com/tls/ca.crt"
export FABRIC_IDENTITY_CERT_PATH="$HOME/xc/fabric-samples/test-network/organizations/peerOrganizations/org1.example.com/users/User1@org1.example.com/msp/signcerts/cert.pem"
export FABRIC_PRIVATE_KEY_PATH="$(find "$HOME/xc/fabric-samples/test-network/organizations/peerOrganizations/org1.example.com/users/User1@org1.example.com/msp/keystore" -type f -name '*_sk' | head -1)"
export FABRIC_PEER_ENDPOINT=localhost:7051
export FABRIC_PEER_HOST_OVERRIDE=peer0.org1.example.com
export FABRIC_CHANNEL=mychannel
export FABRIC_CHAINCODE=trace
export IPFS_API=http://127.0.0.1:5001
mvn -q -pl backend-java exec:java -Dexec.mainClass=lab.blockchain.backend.FabricIpfsDemoMain
```

成功时会同时看到 `cid=...`、`fabricEvents=1` 和 `ipfsRoundTripVerified=true`。这三个值分别证明文件进入 IPFS、链上记录可查询、再按 CID 取回的字节与原文件一致。

## 本地验证

本地只验证 Java 编排层和离线对照模块：

```powershell
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-17.0.21.9-hotspot'
& 'D:\develop\maven\apache-maven-3.10.0\bin\mvn.cmd' -q test
& 'D:\project\blockchain-storage-lab\scripts\run-demo.ps1'
```

真正的 IPFS/Fabric 集成测试需要服务器上的 Kubo、Fabric 网络、证书和私钥，因此不伪造本地成功结果。

## 数据库边界

MySQL 保存页面字段、查询索引和同步状态；Redis 缓存热点查询。二者都可以从 Fabric 和 IPFS 重建，不能成为最终可信源。

## 后续路线

1. 在服务器启动真实 Kubo 和 Fabric test-network。
2. 用 Fabric Gateway 完成第一次 `CreateTrace -> QueryTrace` 端到端调用。
3. 加入 Spring Boot Controller，但保持业务编排和适配器边界不变。
4. 增加 MySQL 索引、Redis 缓存和链上事件监听。
5. 老师确定方案后，再评估多阶段账本、私有数据集合、多 Channel、应用层 Radix-Merkle，以及第一篇论文模块的修改。

每次代码更新都同步修改本 README、对应模块 README 和 `docs/05-data-flow.md`，记录新增组件、数据流和验证命令。

## 来源边界

本仓库根据共享对话中明确出现的两条路线搭建：第一篇提供六个茶叶业务阶段，第二篇提供 IPFS CID 上链的链上链下边界。论文附件的正式题名、作者、原始网络配置和实验数字没有在共享页面中暴露，因此本仓库记录的是可运行的功能架构，不声称恢复论文原始源码或实验结果。
