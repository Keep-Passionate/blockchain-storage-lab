# 来源和复现边界

本仓库的架构起点来自用户提供的共享 ChatGPT 对话：

`https://chatgpt.com/share/6af5160d-3014-83ec-b0aa-9aaa8f3712a2?ogimg=plain`

共享副本明确讨论了：第一篇泰山茶全流程追溯、第二篇普洱茶 5.6.2 的 IPFS 链上链下结合、Java/Spring Boot 主线、Go 链码、Fabric 2.x 路线、MySQL/Redis 和 Docker Compose。上传的论文附件在页面中仅以“上传文件”占位，正式题名、作者、完整网络配置和实验原始数据没有暴露。因此：

- 本仓库实现的是可学习、可运行的功能等价框架；
- 不声称原样恢复作者的源码、Fabric 证书、Docker 配置或实验数字；
- `sha256:<hash>` 是本地教学适配器的内容标识，不等同于真实 IPFS CID；
- `StageBatcher` 是应用层分组实验，不是 SHT-PBFT；
- 论文中的“IFPS”按对话中的解释统一理解为 IPFS 拼写错误，不在代码中沿用该拼写。
