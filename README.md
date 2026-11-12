# Blockchain Storage Lab

这是一个面向学习和后续研究的独立实验仓库。它把共享对话中两篇论文的可复现部分拆成清楚的边界：

- 第二篇普洱茶系统提供“文件进 IPFS、CID 和关键事实进链”的链上链下存储思路；
- 第一篇泰山茶系统提供种植、采摘、加工、仓储、运输、销售六个业务阶段；
- 首版实现一个**自有的教育型区块链**，用来先弄懂区块、哈希、Merkle 根、链下内容寻址和查询闭环；
- Fabric、真实 IPFS、MySQL、Redis 是可替换适配器和部署路线，不把论文中的高级创新冒充成已经复现。

当前首版闭环：

```text
文件字节
  -> ContentAddressedStore（链下对象，sha256:<hash>）
  -> TraceEvent（链上只保存 CID、摘要和业务事实）
  -> Block（previousHash + Merkle root + block hash）
  -> 查询 traceId
  -> 用 CID 取回文件并校验哈希
```

## 先运行最小演示

需要 JDK 17。当前电脑如果还没有 Java，可以在实验室 Linux 服务器上运行；Windows 安装后重新打开终端即可。

```bash
cd core-java
mvn test
mvn -q exec:java -Dexec.mainClass=lab.blockchain.core.DemoMain
```

演示会输出 `cid`、区块哈希、账本校验结果和文件往返校验结果。这里的 `sha256:<hash>` 是教学适配器的 CID 形态，不是完整 IPFS CID；这是为了让每一步都能读懂、可单步调试。真实 IPFS 适配器接入后，`TraceStorageService` 的职责不变。

## 学习顺序

1. 读 `docs/00-learning-path.md`，先理解四类存储和一次写入/查询。
2. 读 `Hashing`、`ContentAddressedStore`、`TraceEvent`、`Block`、`BlockchainLedger`，每次只看一个类。
3. 运行 `DemoMain`，修改描述或文件内容，观察 CID、事件哈希、Merkle 根和区块哈希的变化。
4. 读 `docs/01-architecture.md`，理解以后怎样接 Spring Boot、Fabric、IPFS、MySQL、Redis。
5. 再读 `docs/02-first-paper-extension.md`，从应用层阶段分组开始尝试第一篇的模块修改。

## 目录

```text
core-java/                  可运行的自有区块链和链下对象存储核心
chaincode-go/               未来接入 Fabric 2.x 的最小 Go 链码骨架
infrastructure/             MySQL、Redis、IPFS 的 Docker Compose 开发依赖
docs/                       架构、概念和论文边界说明
scripts/                    本地运行、服务器部署和验证脚本
```

## 目前明确不做的事

首版不声称已经实现 Fabric 底层共识插件、SHT-PBFT、真正的多 Channel 分账本或论文实验数字。`StageBatcher` 只是应用层按环节分组的可读实验模块；它用于理解和后续替换，不能称为 SHT-PBFT。

论文上传附件在共享对话中只显示为“上传文件”，因此本仓库只记录对话中明确出现的架构结论，不猜论文正式题名、作者或未公开的实验参数。见 `docs/03-provenance.md`。
