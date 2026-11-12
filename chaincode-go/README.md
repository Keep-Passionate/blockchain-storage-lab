# Go Fabric chaincode

这里是当前主链路的 Fabric 链码，不再是“未来适配器”。

链码只保存小型溯源事实和 IPFS CID，绝不读取文件字节，也不调用 IPFS、MySQL 或 Redis。

```bash
go mod tidy
go test ./...
```

合约函数：

- `CreateTrace`：创建一条溯源记录；
- `AppendEvent`：向已有溯源记录追加阶段事件；
- `QueryTrace`：按 `traceId` 查询事件历史。

部署时把 `chaincode-go` 根目录作为 `-ccp`，因为 `go.mod` 和 `package main` 位于同一层。
