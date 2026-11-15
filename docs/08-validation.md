# 四层业务验证记录

## 2026-11-15：第一轮真实集成

本轮在实验室已有 Docker / Fabric 网络中增量部署，项目目录为 `~/xc/blockchain-storage-lab`。保留原 `trace` 链码、数据库表和 Docker 数据卷，没有重建网络。四层业务作为 `provenance` 链码部署到 `mychannel`，版本 `1.0`、定义序号 `1`，Org1 与 Org2 均完成批准，两侧提交状态为 `VALID`。

### 已核验环境

| 组件 | 服务器实际值 |
|---|---|
| 系统 | Ubuntu 18.04.6 LTS |
| Java / Maven | OpenJDK 17.0.7 / Maven 3.10.0 |
| Go（宿主构建） | 1.27.4 |
| Docker / Compose | 29.9.3 / 5.7.1 |
| Fabric Peer | 2.5.16，commit `f871cf9` |
| Fabric samples | commit `1b465494fef0123ccc0042209b263b15be447b96` |
| IPFS Kubo | 0.43.1 |
| MySQL | 8.4.13 |

这些是本次执行版本，不表示要求升级实验室整机环境。兼容工具位于项目工具目录或容器中。服务器凭据、私钥及用户身份原文不进入本报告。

### 第一轮结果

虚拟批次 `DEMOTEA-2026-000001` 的 `summary.json` 返回 `status: PASS`，进程退出码为 0。它使用开发快照完成首轮联调；后续 Git 版本复验使用新批次编号，不覆写第一轮记录。报告文件均为 LF，已核验本地字节与 Git 内容一致；两轮同一报告得到相同 CID。

| 检查 | 实际结果 |
|---|---|
| 不支持的操作类型 | `REJECTED`，原因码 `OPERATION_TYPE_NOT_ALLOWED`，MySQL 保存输入 |
| 被拒绝操作的链上查询 | `NOT_FOUND` |
| 重复批次 / 绕过 Java 直接提交违规操作 | 链码拒绝 |
| PASS 与 FAIL 检测记录 | 均提交；FAIL 保留为事实 |
| IPFS 报告往返 / 内存中篡改取回字节 | 原件一致；篡改触发 `REPORT_HASH_MISMATCH` |
| Org1 尝试专家审核 / Org2 批准 FAIL 检测 | 两者都被拒绝 |
| 审计证据 | IPFS 原件取回后的 SHA-256 与链上摘要一致 |
| Fabric 事件 | 首轮接收 7 条，重复重放新增 0 条 |
| 模拟预警 | 新增 2 条，重复补偿扫描新增 0 条 |
| 投影重复重建 | 数据快照一致 |

本地 Java 测试现有 13 个：离线基础 2 个、业务服务 8 个、查询 3 个，均通过。服务器 Go 有 7 个顶层测试，包含 10 个操作规则子用例，均通过。Java 的替身测试还覆盖提交成功但数据库回执更新失败时的恢复；这不代表真实网络已做所有断电/崩溃故障注入。

第一轮原始产物保存在服务器：

```text
runs/provenance-go-test.log
runs/provenance-migration.log
runs/deploy-provenance.log
runs/synthetic-1.log
runs/synthetic/DEMOTEA-2026-000001/summary.json
runs/synthetic/DEMOTEA-2026-000001/snapshot.json
runs/synthetic/DEMOTEA-2026-000001/audit-input.json
```

`snapshot.json` 和审计快照包含实验身份与原始请求，仅保留在实验目录，不直接发布到公开仓库。全部业务记录和报告均为合成数据，不构成农业检测、认证或实际防效证据。

## 最终代码复验

代码提交：`23a7100583b3e080a8b41154a7e0b3d163125ecf`。服务器直接从 GitHub 拉取该版本，工作区干净。已部署链码的三个 Go 源文件与该提交一致；后续提交只更新说明与验证记录。

最终完整批次为 **`DEMOTEA-2026-000003`**：场景退出码 0，`status: PASS`。Java 的 13 个测试也在服务器构建过程中全部通过。第二轮收到 10 条此前未入收件表的事件，其中 7 条来自新批次，另 3 条来自同步期间中断的批次 2。重复重放新增 0 条，本批次新增 2 条模拟预警，重复扫描新增 0 条。

批次 `DEMOTEA-2026-000002` 保留为未完成实验：当时服务器 Git stash 因缺少提交身份而失败，工作区尚未切换，后续场景启动后被中止，留下地块、批次和操作三条记录。未将它标记为成功或删除其链上记录。之后使用临时 Git 身份保存开发快照，再切换到上述提交，使用新编号 3 完整复验。

### 查询与恢复实测

独立查询命令退出码 0，`projectionMatchesLedger: true`、`objectHashesMatch: true`。最终记录数量为：

| 类型 | Fabric | MySQL 投影 |
|---|---:|---:|
| 地块 | 1 | 1 |
| 批次 | 1 | 1 |
| 操作 | 1 | 1 |
| 检测 | 2 | 2 |
| 审核 | 1 | 1 |
| 审计 | 1 | 1 |
| 预警 | 2 | 2 |
| 合计 | 9 | 9 |

恢复测试只针对 `DEMO-OP-3` 的数据库投影。先保存原始投影，再移除这一个精确编号且 `synthetic=true` 的行；链上记录保持不变。默认查询返回非零退出码，报告操作 `ledger=1, database=0`，其余记录和三个 IPFS 对象仍匹配。随后运行 `--rebuild`，查询通过，恢复后的完整投影与删除前字节一致。全部批次提交证据前后完全一致，SHA-256 均为 `1fa4a94557e02152dad64bf98120b27cf7463fbb796cb1677bfa254e457bb00ed`。没有清空整表，也没有更改其他批次。

PASS 报告 CID 为 `QmbnhLwpoZcXjKsZWgBtvCrG1CYsZ6EMmyp8cWUs9cVpXG`，原件 302 字节，SHA-256 为 `1d43d39e37597f09b19fbfe6ace00a7aa8ae4548ffd336e736a2e74e313f67f8`。两份检测报告和本次审计包均由查询程序逐个核验为 `MATCH`。

最终服务器产物：

```text
runs/synthetic-3.log
runs/synthetic/DEMOTEA-2026-000003/{summary.json,snapshot.json,audit-input.json}
runs/query-3.log
runs/query/DEMOTEA-2026-000003-*/summary.json
runs/recovery-3/{summary.json,operation-before.json,missing.log,repaired.log}
.cache/verify-projection-recovery.py
```

Go 链码源文件 SHA-256：`provenance_contract.go` 为 `5697a0227562f0da91a3e5fa79939623a9a8b0dde540261602fcd1578dc20469`，`provenance_contract_test.go` 为 `89a842d365a0d63225a0e9c4548fb07cdf230a3e598efb56326535544e993914`，`trace_contract.go` 为 `b4ca079931abafff037ae2b0536429b847174a3119fd5b03d2174bf00fe43022`。

## 如何自行观察

已有批次查询只读 Fabric / MySQL / IPFS，生成独立摘要，不重复提交实验：

```bash
cd ~/xc/blockchain-storage-lab
bash scripts/query-batch.sh DEMOTEA-2026-000003
```

从链补齐该批次投影：

```bash
bash scripts/query-batch.sh DEMOTEA-2026-000003 --rebuild
```

若现有投影内容与链不同，程序报告冲突，不静默覆盖。`--rebuild` 不重建链下独有的 `p_submission` 原始证据。本次执行后 1、2、3 已使用，新实验可用序号 4：`bash scripts/run-synthetic.sh 4`；之后继续选未占用序号。服务运行期间不要执行 `network.sh down` 或删除数据卷。

## 验证边界

当前是小规模功能与数据流验证，不是性能基准或生产就绪验收。事件订阅为有限时重放，通知为 `SIMULATED`；没有持续监听守护进程、真实通知、自动月审、移动端或正式认证接口。组织范围只演示授权分工。未实现论文的 SHT-PBFT、底层 Radix-Merkle 修改或复现论文性能结果。
