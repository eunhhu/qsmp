#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
SERVER_ENV="$ROOT_DIR/server.env"
EULA_FILE="$ROOT_DIR/eula.txt"
SERVER_JAR="$ROOT_DIR/server.jar"
SERVER_LOCK="$ROOT_DIR/.server-running"

create_ci_server_env() {
  [[ ! -f "$SERVER_ENV" ]] || return 0
  cat > "$SERVER_ENV" <<'EOF'
MC_VERSION=26.1.2
PURPUR_BUILD=latest
JAVA_CMD=auto
MIN_MEMORY=2G
MAX_MEMORY=4G
SERVER_JAR=server.jar
PYTHON_CMD=uv
RESOURCE_PACK_PUBLIC_URL=http://127.0.0.1:25566/qsmp-frontier-pack.zip
EOF
}

accept_ci_eula() {
  [[ ! -f "$EULA_FILE" ]] || return 0
  cat > "$EULA_FILE" <<'EOF'
eula=true
EOF
}

server_is_running() {
  local pid
  [[ -f "$SERVER_LOCK" ]] || return 1
  pid="$(cat "$SERVER_LOCK" 2>/dev/null || true)"
  [[ "$pid" =~ ^[0-9]+$ ]] && kill -0 "$pid" 2>/dev/null
}

resolve_java() {
  source "$SERVER_ENV"
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
}

run_resource_pack_build() {
  local resolved_java="$JAVA_CMD"
  source "$SERVER_ENV"
  JAVA_CMD="$resolved_java"
  : "${PYTHON_CMD:=uv}"
  if [[ "$(basename -- "$PYTHON_CMD")" == "uv" ]]; then
    "$PYTHON_CMD" run "$ROOT_DIR/scripts/build_resource_pack.py"
  else
    "$PYTHON_CMD" "$ROOT_DIR/scripts/build_resource_pack.py"
  fi
}

create_ci_server_env
accept_ci_eula
resolve_java

cd "$ROOT_DIR"

"$JAVA_CMD" "$ROOT_DIR/scripts/PluginManager.java" self-test

if [[ ! -f "$SERVER_JAR" ]]; then
  bash "$ROOT_DIR/server.sh" update
fi

if server_is_running; then
  printf 'server.sh stopped-state update regression: SKIP (server is running)\n'
else
  bash "$ROOT_DIR/tests/server-sh-regression.sh"
fi

if ! server_is_running; then
  bash "$ROOT_DIR/plugins.sh" update
fi

run_resource_pack_build
"$JAVA_CMD" "$ROOT_DIR/scripts/CustomPluginBuilder.java"
"$JAVA_CMD" -cp "$ROOT_DIR/custom-plugins/qsmp-frontier/build/classes" \
  dev.qsmp.frontier.FrontierLogicSelfTest
bash "$ROOT_DIR/server.sh" check
