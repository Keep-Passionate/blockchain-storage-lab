# 学习路线：先看见数据去了哪里

先掌握一条主线：**文件进 IPFS，CID 和关键事实进 Fabric，MySQL 做索引，Redis 做缓存**。

## 四类存储

| 组件 | 保存什么 | 可信边界 |
|---|---|---|
| IPFS | 图片、视频、PDF、检测报告等文件字节 | CID 能校验取回内容，不能证明原始上传内容真实 |
| Fabric | CID、阶段、操作者、时间、元数据摘要 | 多组织共同维护关键事实 |
| MySQL | 页面字段、分页索引、同步状态 | 查询层，可由 Fabric 重建 |
| Redis | 热点查询结果 | 缓存，可删除后回源重建 |

## 推荐阅读顺序

1. 读根目录 `README.md` 和 `docs/05-data-flow.md`。
2. 读 `CreateTrace` 链码，理解 Fabric 保存什么。
3. 读 `IpfsHttpObjectStorage`，理解 Kubo HTTP API 的 `add/cat/pin`。
4. 读 `FabricGatewayLedger`，理解 Java 如何连接 Peer Gateway。
5. 读 `TraceApplicationService`，理解“先 IPFS、后 Fabric”的编排。
6. 最后再读 `core-java` 的区块、哈希和 Merkle 对照实现。

## 一次写入

```text
file bytes
  -> Kubo /api/v0/add
  -> CID
  -> Kubo /api/v0/pin/add
  -> CreateTrace(CID + business facts)
  -> Peer endorse
  -> Raft Orderer order
  -> Peer commit block + World State
```

## 一次查询

```text
traceId
  -> Fabric QueryTrace
  -> CID
  -> Kubo /api/v0/cat
  -> Java SHA-256 verification
  -> response
```

## 需要反复问自己的三个问题

1. 这段数据现在在哪个组件里？
2. 哪个组件有权改变它？
3. 如果它丢失，能否从可信源重建？

这三个问题可以帮助我们避免把文件塞进 Fabric，也避免让 Redis 变成唯一数据源。
