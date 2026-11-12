# 服务器部署

本仓库不内置账号、密码、证书或服务器地址。实验室服务器的工作根目录约定为 `xc`，拿到当前 SSH 信息后：

```bash
export LAB_SERVER='user@lab-server'
export LAB_PATH='~/xc/blockchain-storage-lab'
./scripts/deploy-server.sh
./scripts/verify-server.sh
```

服务器端先准备 JDK 17、Go、Docker、Docker Compose 和 rsync。首次部署建议只运行 `core-java` 演示；老师确认 Fabric 版本和网络拓扑后，再把 Fabric test-network 放入 `infrastructure/fabric`。部署脚本默认跳过 `.git`、运行产物和 Maven target，避免把本地缓存传上服务器。Docker、IPFS、MySQL、Redis 只在服务器的 `xc` 目录和 Docker 数据卷中运行，本地不启动这些服务。
