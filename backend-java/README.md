# Java 后端：四层业务、Fabric、IPFS 与 MySQL

当前主入口是 `lab.blockchain.backend.provenance.SyntheticScenarioMain`。它使用虚拟数据演示批次、地块、操作、检测，以及审核、审计和模拟预警。这里是命令行实验后端，尚无 HTTP Controller、移动端或真实质量认证接口。

## 模块如何分工

```text
SyntheticScenarioMain                       虚拟场景与检查，显式触发事件重放和补偿扫描
  └─ ProvenanceService                      请求日志、提交、对账、报告核验、投影重放
       ├─ ProvenanceRules                   实验标记、入口 ID 与批次编号格式
       ├─ ProvenanceLedger                 业务账本接口
       │    └─ FabricProvenanceLedger       Gateway / 两个组织身份 / 合约事件
       ├─ ObjectStoragePort
       │    └─ IpfsHttpObjectStorage        Kubo add / pin / cat
       └─ ProvenanceStore
            └─ JdbcProvenanceStore          提交日志、七类投影、事件收件
```

业务代码在 `src/main/java/lab/blockchain/backend/provenance/`。建议先读场景，再读规则、服务和两个外部适配器，最后对照 `chaincode-go/provenance_contract.go` 与 `infrastructure/mysql/migrations/002_provenance.sql`。

`ProvenanceRules` 只处理入口格式与实验数据标记；操作的引用、时间、允许类型等关键规则由链码负责。服务先用 `CheckOperation` 只读预校验，正式 `PutOperation` 又执行同一套规则，不能因 Java 已检查就省略链上约束。

## 一次业务调用与回执

`ProvenanceService.write(requestId, kind, data)` 为普通记录入口，`inspection(requestId, fields, reportBytes)` 为检测报告入口。入口格式检查后，先在 MySQL 保存原始结构化请求。操作预校验明确拒绝时，回执为 `REJECTED`，附原因码；它不是一笔已提交的 Fabric 交易。

报告入口计算原文字节 SHA-256，经 IPFS `add/pin` 得到 CID，再把关键字段提交到 Fabric。`FabricProvenanceLedger.submit` 等待有效提交结果；只有确认后才能将回执设为 `COMMITTED`，再写查询投影。调用异常保留 `UNKNOWN`，便于查链对账，不把网络失败解释成农业违规。

相同请求再次进入时，服务先读已有链上对象并比较内容，避免把“上次已成功但回执丢失”误报为新拒绝。对账失败或内容冲突会报错，不盲目再次提交。IPFS、Fabric 与 MySQL 之间没有一个覆盖三者的原子事务。

`verifiedReport(inspectionId)` 从链上读取 CID 与摘要，再从 IPFS 取回原件；摘要不一致时报错。`rebuild(batchId)` 只幂等补齐查询投影，不清空 `p_submission`，遇到投影内容冲突不静默覆盖。

## 事件与预警的实际范围

`FabricProvenanceLedger.replay` 调用真实 Gateway 事件订阅接口。场景给每次调用设置 5 秒时限、最多 10000 个事件，使用最近收件区块作包含式恢复。`JdbcProvenanceStore.acceptEvent` 在一个事务中写投影与收件，按 `txID + eventName` 去重。

随后 `SyntheticScenarioMain` 扫描本批次已提交记录，为 `FAIL` 检测和 `FLAGGED` 审计创建 `EV-<来源 txId>` 预警；已有 ID 时跳过。收件与补偿是两个步骤，不能把收件成功等同于消息送达。

预警始终标为 `SIMULATED`，不真实发送通知。场景检查重复重放和重复扫描；目前没有全天候事件进程、月审调度或真实通知队列，也不把幂等检查称为已执行崩溃故障注入。

## 服务器运行

依赖已有 Kubo、MySQL、Fabric test-network、`mychannel` 与名为 `provenance` 的链码；脚本和完整部署顺序见 [项目 README](../README.md)。在仓库根目录运行：

```bash
source scripts/lab-env.sh
bash scripts/migrate-db.sh
bash scripts/fabric-network.sh deploy-provenance
bash scripts/run-synthetic.sh 1
```

`run-synthetic.sh` 构建 Maven 反应堆，并运行：

```bash
mvn -q -pl backend-java exec:java \
  -Dexec.mainClass=lab.blockchain.backend.provenance.SyntheticScenarioMain \
  -Dexec.args="1"
```

参数是新的实验序号。输入为 `data/synthetic/scenario.json` 和两份虚拟报告，输出为 `runs/synthetic/<batchId>/` 下的 `summary.json`、`snapshot.json`、`audit-input.json`。成功与否以实际检查和输出为准；不要覆盖已有实验编号。

新增的 `ProvenanceQueryMain` 用于独立查询已有批次：

```bash
bash scripts/query-batch.sh DEMOTEA-2026-000003
bash scripts/query-batch.sh DEMOTEA-2026-000003 --rebuild
```

默认查询只读链和数据库，并核验 IPFS 检测报告与审计证据；`--rebuild` 只补齐投影，不覆盖冲突行或修改请求日志。它将筛选后的查询摘要写入 `runs/query/`，完整记录仍参与一致性比较。脚本使用已编译的类，不重复构建；只读查询与指定投影缺失恢复已在服务器实测通过。

环境变量由 `lab-env.sh` 与适配器共同使用：

| 变量 | 用途 |
|---|---|
| `LAB_ROOT` | fixture 与运行产物所在仓库根目录 |
| `MYSQL_URL`、`MYSQL_USER`、`MYSQL_PASSWORD` | JDBC 连接；凭据来自未提交的服务器 `.env` |
| `FABRIC_SAMPLES_DIR` | 实验网络目录；从中读取 Org1 / Org2 的 User1 证书与私钥 |
| `FABRIC_CHANNEL` | 默认 `mychannel` |
| `PROVENANCE_CHAINCODE` | 默认 `provenance`；客户端显式选择 `ProvenanceContract` |
| `FABRIC_ORG1_ENDPOINT`、`FABRIC_ORG2_ENDPOINT` | 默认本机 `7051` / `9051` Gateway 端点 |
| `IPFS_API` | 默认 `http://127.0.0.1:5001` |

证书和私钥由真实 X.509 身份提供，业务里的 `operatorId` 不能代替认证。Org1 录入生产与检测，Org2 演示审核与监管；这仍不是细粒度人员授权或资质认证。默认 JDBC URL 只面向本机实验容器，不应直接作为跨机生产连接配置。

## 验证与旧代码

本地与服务器的后端 11 个 Java 测试和 core 2 个测试均通过；Git 版本完整场景 `DEMOTEA-2026-000003` 返回 `PASS`，包括报告核验、审核约束、真实事件收件与两条模拟预警。独立查询和指定投影缺失恢复也通过，提交证据保持不变。版本与产物见 [验证记录](../docs/08-validation.md)；内存替身检查与真实网络结果分别记录。

```bash
# 在仓库根目录运行本地 Java 检查。
mvn -q test
```

旧 `TraceApplicationService`、`FabricGatewayLedger` 与 `FabricIpfsDemoMain` 保留为 CID 上链入门示例，不是当前四层业务入口。Redis 是可选扩展；当前请求流没有依赖它，也没有实现 HTTP 页面或认证平台连接。
