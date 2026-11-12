# Go chaincode boundary

This directory is intentionally small. It is the future Fabric adapter for the Java service, not the implementation of the educational chain.

```bash
go mod tidy
go test ./...
```

The contract exposes `CreateTrace`, `AppendEvent`, and `QueryTrace`. It stores JSON event metadata and a content CID; it never reads a file, MySQL, Redis, or IPFS. Connect it only after the core-java flow is understood.
