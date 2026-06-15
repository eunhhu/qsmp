#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
CONFIG_FILE="$ROOT_DIR/server.env"

[[ -f "$CONFIG_FILE" ]] || {
  printf 'Error: Missing %s\n' "$CONFIG_FILE" >&2
  exit 1
}

source "$CONFIG_FILE"
: "${JAVA_CMD:=auto}"

if [[ "$JAVA_CMD" == "auto" ]]; then
  if [[ -x "$ROOT_DIR/runtime/java/bin/java" ]]; then
    JAVA_CMD="$ROOT_DIR/runtime/java/bin/java"
  elif [[ -x "$ROOT_DIR/runtime/java/bin/java.exe" ]]; then
    JAVA_CMD="$ROOT_DIR/runtime/java/bin/java.exe"
  else
    JAVA_CMD="java"
  fi
fi

cd "$ROOT_DIR"
"$JAVA_CMD" "$ROOT_DIR/scripts/PluginManager.java" "${1:-update}"
