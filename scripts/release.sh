#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
RELEASE_ASSETS_DIR="$SCRIPT_DIR/release-assets"

die() { echo "[release] $*" >&2; exit 1; }

VERSION="${VERSION:-$(cat "$REPO_ROOT/VERSION" 2>/dev/null || echo "dev")}"
[[ "$VERSION" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "VERSION must match vX.Y.Z (got: $VERSION)"

if [[ -z "${ARCH:-}" ]]; then
  case "$(uname -m)" in
    x86_64|amd64) ARCH=amd64 ;;
    arm64|aarch64) ARCH=arm64 ;;
    *) die "cannot detect ARCH from $(uname -m); pass ARCH=amd64|arm64" ;;
  esac
fi

PLATFORM="linux/$ARCH"
IMAGE_REF="agent-platform-runner:$VERSION"
BUNDLE_NAME="agent-platform-runner-${VERSION}-linux-${ARCH}"
BUNDLE_TAR="$REPO_ROOT/dist/release/${BUNDLE_NAME}.tar.gz"
RELEASE_BASE_IMAGE_LOCAL="${RELEASE_BASE_IMAGE_LOCAL:-}"
RELEASE_BASE_IMAGE="${RELEASE_BASE_IMAGE:-eclipse-temurin:21-jre-jammy}"
BASE_IMAGE="$RELEASE_BASE_IMAGE"

if [[ -n "$RELEASE_BASE_IMAGE_LOCAL" ]]; then
  BASE_IMAGE="$RELEASE_BASE_IMAGE_LOCAL"
fi

echo "[release] VERSION=$VERSION ARCH=$ARCH PLATFORM=$PLATFORM"
if [[ -n "$RELEASE_BASE_IMAGE_LOCAL" ]]; then
  echo "[release] BASE_IMAGE_LOCAL=$RELEASE_BASE_IMAGE_LOCAL"
else
  echo "[release] BASE_IMAGE=$RELEASE_BASE_IMAGE"
fi

command -v mvn >/dev/null 2>&1 || die "mvn is required"
command -v docker >/dev/null 2>&1 || die "docker is required"

if [[ -n "$RELEASE_BASE_IMAGE_LOCAL" ]]; then
  if ! docker image inspect "$RELEASE_BASE_IMAGE_LOCAL" >/dev/null 2>&1; then
    die "local base image not found: $RELEASE_BASE_IMAGE_LOCAL. Pull a reachable base image first, then tag it to $RELEASE_BASE_IMAGE_LOCAL before rerunning make release"
  fi
fi

TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/agent-platform-runner-release.XXXXXX")"
trap 'rm -rf "$TMP_DIR"' EXIT

IMAGES_DIR="$TMP_DIR/images"
mkdir -p "$IMAGES_DIR"

echo "[release] packaging jar on host..."
(
  cd "$REPO_ROOT"
  mvn -B -DskipTests clean package
)

JAR_CANDIDATES=()
while IFS= read -r jar; do
  JAR_CANDIDATES+=("$jar")
done < <(find "$REPO_ROOT/target" -maxdepth 1 -type f -name '*.jar' ! -name '*.jar.original' | sort)

if [[ "${#JAR_CANDIDATES[@]}" -eq 0 ]]; then
  die "no runnable jar found under target/ after mvn package"
fi

if [[ "${#JAR_CANDIDATES[@]}" -ne 1 ]]; then
  printf '[release] found multiple jar candidates:\n' >&2
  printf '  %s\n' "${JAR_CANDIDATES[@]}" >&2
  die "expected exactly one runnable jar under target/"
fi

APP_JAR="${JAR_CANDIDATES[0]}"
APP_JAR_REL="${APP_JAR#$REPO_ROOT/}"
[[ "$APP_JAR_REL" != "$APP_JAR" ]] || die "failed to resolve APP_JAR relative path"

echo "[release] using jar: $APP_JAR_REL"

echo "[release] building image..."
docker buildx build \
  --platform "$PLATFORM" \
  --file "$RELEASE_ASSETS_DIR/Dockerfile.release" \
  --tag "$IMAGE_REF" \
  --build-arg "BASE_IMAGE=$BASE_IMAGE" \
  --build-arg "APP_JAR=$APP_JAR_REL" \
  --output "type=docker,dest=$IMAGES_DIR/agent-platform-runner.tar" \
  "$REPO_ROOT"

BUNDLE_ROOT="$TMP_DIR/agent-platform-runner"
mkdir -p \
  "$BUNDLE_ROOT/images" \
  "$BUNDLE_ROOT/configs" \
  "$BUNDLE_ROOT/runtime/agents" \
  "$BUNDLE_ROOT/runtime/teams" \
  "$BUNDLE_ROOT/runtime/models" \
  "$BUNDLE_ROOT/runtime/providers" \
  "$BUNDLE_ROOT/runtime/tools" \
  "$BUNDLE_ROOT/runtime/mcp-servers" \
  "$BUNDLE_ROOT/runtime/viewport-servers" \
  "$BUNDLE_ROOT/runtime/viewports" \
  "$BUNDLE_ROOT/runtime/skills-market" \
  "$BUNDLE_ROOT/runtime/schedules" \
  "$BUNDLE_ROOT/runtime/chats" \
  "$BUNDLE_ROOT/runtime/root" \
  "$BUNDLE_ROOT/runtime/pan"

cp "$RELEASE_ASSETS_DIR/compose.release.yml" "$BUNDLE_ROOT/compose.release.yml"
cp "$RELEASE_ASSETS_DIR/start.sh" "$BUNDLE_ROOT/start.sh"
cp "$RELEASE_ASSETS_DIR/stop.sh" "$BUNDLE_ROOT/stop.sh"
cp "$RELEASE_ASSETS_DIR/README.txt" "$BUNDLE_ROOT/README.txt"
cp "$RELEASE_ASSETS_DIR/.env.example" "$BUNDLE_ROOT/.env.example"
cp "$IMAGES_DIR/agent-platform-runner.tar" "$BUNDLE_ROOT/images/"

while IFS= read -r file; do
  rel="${file#$REPO_ROOT/configs/}"
  mkdir -p "$BUNDLE_ROOT/configs/$(dirname "$rel")"
  cp "$file" "$BUNDLE_ROOT/configs/$rel"
done < <(find "$REPO_ROOT/configs" -type f \( -name '*.example.yml' -o -name '*.example.yaml' -o -name '*.example.pem' \) | sort)

copy_example_dir() {
  local source_dir="$1"
  local target_dir="$2"
  if [[ -d "$source_dir" ]]; then
    cp -R "$source_dir"/. "$target_dir"/
  fi
}

copy_example_dir "$REPO_ROOT/example/agents" "$BUNDLE_ROOT/runtime/agents"
copy_example_dir "$REPO_ROOT/example/teams" "$BUNDLE_ROOT/runtime/teams"
copy_example_dir "$REPO_ROOT/example/models" "$BUNDLE_ROOT/runtime/models"
copy_example_dir "$REPO_ROOT/example/providers" "$BUNDLE_ROOT/runtime/providers"
copy_example_dir "$REPO_ROOT/example/tools" "$BUNDLE_ROOT/runtime/tools"
copy_example_dir "$REPO_ROOT/example/mcp-servers" "$BUNDLE_ROOT/runtime/mcp-servers"
copy_example_dir "$REPO_ROOT/example/viewport-servers" "$BUNDLE_ROOT/runtime/viewport-servers"
copy_example_dir "$REPO_ROOT/example/viewports" "$BUNDLE_ROOT/runtime/viewports"
copy_example_dir "$REPO_ROOT/example/schedules" "$BUNDLE_ROOT/runtime/schedules"

sed -i.bak "s/^RUNNER_VERSION=.*/RUNNER_VERSION=$VERSION/" "$BUNDLE_ROOT/.env.example"
rm -f "$BUNDLE_ROOT/.env.example.bak"

chmod +x "$BUNDLE_ROOT/start.sh" "$BUNDLE_ROOT/stop.sh"

mkdir -p "$(dirname "$BUNDLE_TAR")"
tar -czf "$BUNDLE_TAR" -C "$TMP_DIR" agent-platform-runner

echo "[release] done: $BUNDLE_TAR"
