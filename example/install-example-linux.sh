#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
EXAMPLE_DIR="$ROOT_DIR/example"

DIRS=(agents teams models mcp-servers viewport-servers viewports tools skills schedules providers)

log() {
  printf '[install-example][linux] %s\n' "$*"
}

remove_legacy_image_registration() {
  local legacy_file="$ROOT_DIR/mcp-servers/image.yml"
  if [ -f "$legacy_file" ]; then
    rm -f "$legacy_file"
    log "removed deprecated MCP sample: $legacy_file (use mcp-servers/imagine.yml)"
  fi
}

count_files() {
  local dir="$1"
  if [ ! -d "$dir" ]; then
    echo 0
    return
  fi
  find "$dir" -type f | wc -l | tr -d ' '
}

log "example source: $EXAMPLE_DIR"
log "target root: $ROOT_DIR"
remove_legacy_image_registration

for dir in "${DIRS[@]}"; do
  src="$EXAMPLE_DIR/$dir"
  dest="$ROOT_DIR/$dir"

  if [ ! -d "$src" ]; then
    log "skip missing source dir: $src"
    continue
  fi

  mkdir -p "$dest"
  src_count="$(count_files "$src")"
  # Sync all files except README.md to avoid copying local helper scripts.
  (cd "$src" && tar --exclude='README.md' -cf - .) | (cd "$dest" && tar -xf -)
  dest_count="$(count_files "$dest")"

  log "synced $dir (source files=$src_count, target files=$dest_count)"
done

log "done"
