# Infrastructure notes

`docker-compose.yml` starts the server-side application dependencies: MySQL, Redis and a Kubo IPFS node. Fabric is started by the official `fabric-samples/test-network` scripts, because it needs organization identities, channel configuration, orderer/peer certificates and chaincode lifecycle commands.

Local smoke test:

```bash
docker compose --env-file .env up -d
docker compose ps
curl http://127.0.0.1:5001/api/v0/version
```

On the lab server, copy this directory, set a private `.env`, and bind ports only as the server policy allows. Do not commit passwords or certificates. The application uses the real Kubo HTTP API; the local `sha256:<hash>` store remains only as an offline reference.

When the Java service is added, its default endpoints should point to `mysql:3306`, `redis:6379`, and the IPFS API at `http://ipfs:5001/api/v0` from inside the Compose network.
