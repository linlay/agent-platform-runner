#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
EXAMPLE_DIR="$ROOT_DIR/example"

DIRS=(agents teams models mcp-servers viewports tools skills)

log() {
  printf '[install-example][linux] %s\n' "$*"
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

for dir in "${DIRS[@]}"; do
  src="$EXAMPLE_DIR/$dir"
  dest="$ROOT_DIR/$dir"

  if [ ! -d "$src" ]; then
    log "skip missing source dir: $src"
    continue
  fi

  mkdir -p "$dest"
  src_count="$(count_files "$src")"
  cp -R "$src"/. "$dest"/
  dest_count="$(count_files "$dest")"

  log "synced $dir (source files=$src_count, target files=$dest_count)"
done

log "done"
