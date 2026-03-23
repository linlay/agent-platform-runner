agent-platform-runner release bundle
===================================

1. Copy `.env.example` to `.env`.
2. Adjust `.env` as needed. Runtime directories such as `AGENTS_DIR`, `OWNER_DIR`, `SKILLS_MARKET_DIR`, and `SCHEDULES_DIR` default to `./runtime/*`, and you may override them to any external host paths. If Container Hub runs on the host, keep `AGENT_CONTAINER_HUB_BASE_URL=http://host.docker.internal:11960`.
3. Copy the config templates you need under `configs/`, for example:
   - `configs/container-hub.example.yml` -> `configs/container-hub.yml`
   - `configs/bash.example.yml` -> `configs/bash.yml`
   - `configs/cors.example.yml` -> `configs/cors.yml`
   - `configs/local-public-key.example.pem` -> `configs/local-public-key.pem`
4. Make sure the external Docker network `zenmind-network` already exists.
5. Start with `./start.sh`. It will create the effective runtime directories from `.env` automatically when they are missing.
6. Stop with `./stop.sh`.

Bundle contents:
- `images/agent-platform-runner.tar`: offline Docker image
- `configs/`: safe-to-distribute config templates only
- no precreated `runtime/`: host runtime directories come from `.env`, default to `./runtime/*`, and are auto-created by `./start.sh`
