agent-platform-runner release bundle
===================================

1. Copy `.env.example` to `.env`.
2. Adjust `.env` as needed. Relative `./runtime/*` paths work out of the box.
3. Copy the config templates you need under `configs/`, for example:
   - `configs/container-hub.example.yml` -> `configs/container-hub.yml`
   - `configs/bash.example.yml` -> `configs/bash.yml`
   - `configs/cors.example.yml` -> `configs/cors.yml`
   - `configs/local-public-key.example.pem` -> `configs/local-public-key.pem`
4. Make sure the external Docker network `zenmind-network` already exists.
5. Start with `./start.sh`.
6. Stop with `./stop.sh`.

Bundle contents:
- `images/agent-platform-runner.tar`: offline Docker image
- `configs/`: safe-to-distribute config templates only
- `runtime/`: starter runtime directories populated from `example/`
