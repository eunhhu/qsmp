#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
source "$ROOT_DIR/server.env"
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
"$JAVA_CMD" "$ROOT_DIR/scripts/WorldManager.java" "$@"
