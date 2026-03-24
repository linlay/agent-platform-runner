#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="$SCRIPT_DIR/.env"
COMPOSE_FILE="$SCRIPT_DIR/compose.release.yml"
IMAGES_DIR="$SCRIPT_DIR/images"
NETWORK_NAME="zenmind-network"

die() { echo "[start] $*" >&2; exit 1; }

[[ -f "$ENV_FILE" ]] || die "missing .env (copy from .env.example first)"
[[ -f "$COMPOSE_FILE" ]] || die "missing compose.release.yml"

command -v docker >/dev/null 2>&1 || die "docker is required"
docker compose version >/dev/null 2>&1 || die "docker compose v2 is required"
docker network inspect "$NETWORK_NAME" >/dev/null 2>&1 || die "missing docker network '$NETWORK_NAME'"

cd "$SCRIPT_DIR"

set -a
. "$ENV_FILE"
set +a

RUNNER_VERSION="${RUNNER_VERSION:-latest}"
IMAGE_REF="agent-platform-runner:$RUNNER_VERSION"
IMAGE_TAR="$IMAGES_DIR/agent-platform-runner.tar"

load_image() {
  local ref="$1"
  local tar="$2"
  if docker image inspect "$ref" >/dev/null 2>&1; then
    return 0
  fi
  [[ -f "$tar" ]] || die "missing image tar: $tar"
  docker load -i "$tar" >/dev/null
  docker image inspect "$ref" >/dev/null 2>&1 || die "failed to load image: $ref"
}

ensure_dir() {
  local path="$1"
  mkdir -p "$path"
}

load_image "$IMAGE_REF" "$IMAGE_TAR"

ensure_dir "$SCRIPT_DIR/configs"
ensure_dir "${PROVIDERS_DIR:-$SCRIPT_DIR/runtime/providers}"
ensure_dir "${MODELS_DIR:-$SCRIPT_DIR/runtime/models}"
ensure_dir "${MCP_SERVERS_DIR:-$SCRIPT_DIR/runtime/mcp-servers}"
ensure_dir "${VIEWPORT_SERVERS_DIR:-$SCRIPT_DIR/runtime/viewport-servers}"
ensure_dir "${OWNER_DIR:-$SCRIPT_DIR/runtime/owner}"
ensure_dir "${AGENTS_DIR:-$SCRIPT_DIR/runtime/agents}"
ensure_dir "${TEAMS_DIR:-$SCRIPT_DIR/runtime/teams}"
ensure_dir "${ROOT_DIR:-$SCRIPT_DIR/runtime/root}"
ensure_dir "${SCHEDULES_DIR:-$SCRIPT_DIR/runtime/schedules}"
ensure_dir "${CHATS_DIR:-$SCRIPT_DIR/runtime/chats}"
ensure_dir "${PAN_DIR:-$SCRIPT_DIR/runtime/pan}"
ensure_dir "${SKILLS_MARKET_DIR:-$SCRIPT_DIR/runtime/skills-market}"

export RUNNER_VERSION
docker compose -f "$COMPOSE_FILE" up -d

echo "[start] started agent-platform-runner $RUNNER_VERSION"
echo "[start] endpoint: http://127.0.0.1:${HOST_PORT:-11949}"
