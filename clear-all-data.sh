#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DIRS=(agents teams tools skills mcp-servers viewport-servers chats models viewports schedules)

printf '[clear-all-data] root: %s\n' "$ROOT_DIR"

for dir in "${DIRS[@]}"; do
  target="$ROOT_DIR/$dir"

  if [ ! -e "$target" ]; then
    printf '[clear-all-data] skip (not found): %s\n' "$dir"
    continue
  fi

  read -r -p "[clear-all-data] delete '$dir'? input y to confirm: " answer
  if [ "$answer" = "y" ]; then
    rm -rf "$target"
    printf '[clear-all-data] deleted: %s\n' "$dir"
  else
    printf '[clear-all-data] skipped: %s\n' "$dir"
  fi
done

printf '[clear-all-data] done\n'
