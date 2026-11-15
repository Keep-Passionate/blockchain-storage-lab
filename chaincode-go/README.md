# Go 链码：四层溯源与不可变业务记录

当前主业务是 `provenance_contract.go` 中的 `ProvenanceContract`。它在一个 Channel 中保存七类不可变记录：`plot`、`batch`、`operation`、`inspection`、`review`、`audit`、`alert`。四层是业务关系，不代表四个 Channel 或四套共识。

链码保存小型业务事实、IPFS CID 和原文件 SHA-256。它不读取报告字节，不访问 IPFS / MySQL / Redis，不向真实用户发送通知，也不自行证明报告结论真实。

## 写入与只读接口

七个 `Put*` 方法均接收一个业务 JSON 字符串，返回包含 `kind`、`id`、`batchId`、`data`、`txId`、`submitterMsp`、`submitterId` 的完整记录。交易号与提交身份来自 Fabric 上下文，不能由调用者伪造业务字段替代。

| 方法 | 主要约束 | 演示写入组织 |
|---|---|---|
| `PutPlot` | 品种、环境、海拔等字段；快照 ID 不可覆盖 | Org1MSP |
| `PutBatch` | `regionCode-year-六位序号`；地块存在且区域相符；时间合法 | Org1MSP |
| `PutOperation` | 批次与地块相符；类型、时间、规则版本合法 | Org1MSP |
| `PutInspection` | 报告 CID / SHA-256、检测时间、PASS / FAIL 结论 | Org1MSP |
| `PutReview` | 检测存在且属于同批次；FAIL 不能 APPROVE | Org2MSP |
| `PutAudit` | 被审记录存在且同批次；证据锚点；CLEAR / FLAGGED | Org2MSP |
| `PutAlert` | 来源为同批次 FAIL 检测或 FLAGGED 审计；仅 SIMULATED | Org2MSP |

只读方法：

- `CheckOperation(payloadJSON)`：返回 `valid` 与 `reasonCodes`，不写入状态。`PutOperation` 仍会重新执行完整校验；权限或基础设施异常不会被假装成普通业务原因码。
- `ReadRecord(kind, id)`：按类型与编号读取一条完整记录。
- `ListBatch(batchId)`：返回地块快照与该批次关联记录，供投影和补偿扫描使用；当前有列表规模上限，尚未分页。

允许操作类型为 `PLANTING`、`IRRIGATION`、`HARVEST`、`PROCESSING`、`STORAGE`、`OBSERVATION`。当前只接受 `schemaVersion=1`、`synthetic=true` 的实验输入，规则版本为 `trace-rules-v1`。这些约束用于功能实验，不是正式农业法规或安全质量认证。

同一对象 ID 不可覆盖。检测 FAIL 本身可以上链，专家意见通过新 `review` 记录追加；需要更正报告时创建新检测与新证据，不改掉历史事实。一个批次当前只关联一个不可变地块快照，尚未实现拆合批或多地块来源。

## 事件、预警与链下拒绝证据

| 已提交记录 | 合约事件 |
|---|---|
| `FAIL` 检测 | `InspectionFailed` |
| `FLAGGED` 审计 | `AuditFlagged` |
| 其他正常提交 | `RecordCommitted` |

事件携带完整业务记录。链下服务有限时订阅后写 MySQL inbox，并另行扫描异常来源记录，以 `EV-<来源 txId>` 生成幂等模拟预警。链码验证预警来源及其结论；确定性 ID 是当前场景编排策略，不是链码替用户发送通知。

业务拒绝不会留下有效操作或有效合约事件。链下 `p_submission` 负责保留被拒绝操作的原始输入和原因码；若需要外部核对点，由审计把选定证据快照存 IPFS 并锚定到链上。不要用“报错交易会顺便留证”的假设替代这条流程。

## Fabric 与本合约的责任

Gateway 请求 Peer 背书，Peer 模拟执行链码并生成读写集；客户端签名后，Gateway 将背书交易广播给 Orderer。Orderer 排序形成区块，提交 Peer 验证背书与读版本，再更新有效业务状态。

因此“模拟执行返回了结果”不等于“上链成功”，最终应检查有效提交状态。Orderer 不解释本文件的业务规则；合约也没有替换 Fabric 排序服务、共识协议或账本底层结构。

当前组织限制是教学中的职责分离。Org1 的生产人员和检测人员尚未进一步区分；Org2 成员身份不代表已取得专家或监管资质。单 Channel 内的公共记录也不代表已有细粒度读取隔离或敏感资料授权方案。

## 测试与部署

在服务器或具备相应 Go 工具链的环境，从此目录运行：

```bash
go test ./...
```

截至本次文档更新，服务器 Go 的 7 个顶层测试、10 个子用例通过；`provenance v1.0 / sequence 1` 已有效部署，第一轮四层场景返回 `PASS`。最终 Git 文件同步后的复验仍在进行，具体版本与产物见 [验证记录](../docs/08-validation.md)。合约测试、真实网络提交和后续查询入口分别验证。

从仓库根目录加载实验环境并部署：

```bash
source scripts/lab-env.sh
bash scripts/fabric-network.sh deploy-provenance
```

该命令默认以链码名 `provenance`、版本 `1.0`、定义序号 `1` 部署到现有 `mychannel`。后续升级需核对已提交定义，再设置 `PROVENANCE_VERSION`、`PROVENANCE_SEQUENCE`。这条命令不要求关闭网络或删除旧数据。`chaincode-go` 根目录是部署路径，因为 `go.mod` 与 `package main` 在这里；保留 `go.sum`，不将 `vendor/`、证书、私钥或生成产物提交到仓库。

## 历史兼容入口

`trace_contract.go` 的 `main` 同时注册旧 `TraceContract` 和新 `ProvenanceContract`。旧 `CreateTrace`、`AppendEvent`、`QueryTrace` 保留用于历史 CID 示例；当前 Java 客户端显式选择 `ProvenanceContract`，不能把两个合约的数据模型混在一起。

本版没有销售、防控算法、自动月审、SHT-PBFT 或 Fabric 底层 Radix 改造。论文页码、设计依据与复现边界见 [来源说明](../docs/03-provenance.md)。
