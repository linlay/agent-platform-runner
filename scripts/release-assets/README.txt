agent-platform-runner release bundle
===================================

1. Copy `.env.example` to `.env`.
2. Adjust `.env` as needed. `REGISTRIES_DIR` defaults to `./runtime/registries`, `MEMORY_DIR` defaults to `./runtime/memory`, and the other `*_DIR` runtime directories default to `./runtime/*`; you may override any of them to external absolute host paths. Local runs read these paths directly. Docker runs keep container paths fixed under `/opt/*`, and sandbox mounts read the original host paths directly from the container environment variables populated by `.env`. If Container Hub runs on the host, keep `AGENT_CONTAINER_HUB_BASE_URL=http://host.docker.internal:11960`, and prefer real host-accessible paths in `*_DIR` plus `MEMORY_DIR`. The corresponding container registry paths are fixed under `/opt/registries/*`.
3. Copy the config templates you need under `configs/`, for example:
   - `configs/container-hub.example.yml` -> `configs/container-hub.yml`
   - `configs/bash.example.yml` -> `configs/bash.yml`
   - `configs/cors.example.yml` -> `configs/cors.yml`
   - `configs/local-public-key.example.pem` -> `configs/local-public-key.pem` (default local public key file mode; if you use JWKS-only, set `AGENT_AUTH_LOCAL_PUBLIC_KEY_FILE=` in `.env`)
4. Make sure the external Docker network `zenmind-network` already exists.
5. Start with `./start.sh`. It will create the effective runtime directories from `.env` automatically when they are missing.
6. Stop with `./stop.sh`.

Bundle contents:
- `images/agent-platform-runner.tar`: offline Docker image
- `configs/`: safe-to-distribute config templates only
- no precreated `runtime/`: host runtime directories come from `.env`, `REGISTRIES_DIR` defaults to `./runtime/registries`, `MEMORY_DIR` defaults to `./runtime/memory`, the rest default to `./runtime/*`, and all are auto-created by `./start.sh`
- registry paths are grouped under `registries/`: keep static startup config in `configs/`, and use `registries/` as the parent for `providers/`, `models/`, `mcp-servers/`, and `viewport-servers/`
