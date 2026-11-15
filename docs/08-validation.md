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

虚拟批次 `DEMOTEA-2026-000001` 的 `summary.json` 返回 `status: PASS`，进程退出码为 0。它使用开发快照完成首轮联调；该轮报告文件行尾为 CRLF，Git 规范化后的 LF 报告字节会得到不同 CID，因此后续正式复验使用新批次编号，不覆写第一轮记录。

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

最终 Git 版本、批次 2、只读查询及指定投影缺失恢复将在服务器执行后补充。不能仅凭脚本存在推断已经通过。

## 如何自行观察

已有批次查询只读 Fabric / MySQL / IPFS，生成独立摘要，不重复提交实验：

```bash
cd ~/xc/blockchain-storage-lab
bash scripts/query-batch.sh DEMOTEA-2026-000001
```

从链补齐该批次投影：

```bash
bash scripts/query-batch.sh DEMOTEA-2026-000001 --rebuild
```

若现有投影内容与链不同，程序报告冲突，不静默覆盖。`--rebuild` 不重建链下独有的 `p_submission` 原始证据。新实验使用未占用序号：`bash scripts/run-synthetic.sh 3`；服务运行期间不要执行 `network.sh down` 或删除数据卷。

## 验证边界

当前是小规模功能与数据流验证，不是性能基准或生产就绪验收。事件订阅为有限时重放，通知为 `SIMULATED`；没有持续监听守护进程、真实通知、自动月审、移动端或正式认证接口。组织范围只演示授权分工。未实现论文的 SHT-PBFT、底层 Radix-Merkle 修改或复现论文性能结果。
