# Backend boundary

这里先放业务编排的接口，不急着引入 Spring Cloud、Nacos 或 Vue。它们分别解决微服务治理和页面问题，不能替代 Fabric Client 或 IPFS Client。

建议最终的调用链是：

```text
HTTP Controller -> TraceApplicationService
                 -> ObjectStoragePort (IPFS)
                 -> LedgerPort (Fabric Gateway 或本地链)
                 -> TraceRepository (MySQL)
                 -> TraceCache (Redis)
```

`core-java` 是当前可运行的本地实现；本目录是未来 Spring Boot 适配层的放置位置。先把接口读懂，再接框架，便于定位问题。
