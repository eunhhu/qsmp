#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
CONFIG_FILE="$ROOT_DIR/server.env"
LOCK_FILE="$ROOT_DIR/.server-running"
INSTALL_FILE="$ROOT_DIR/.purpur-install"
USER_AGENT="qsmp-bootstrap/1.0 (Purpur server setup)"
REQUIRED_JAVA_MAJOR=25

fail() {
  printf 'Error: %s\n' "$*" >&2
  exit 1
}

load_config() {
  [[ -f "$CONFIG_FILE" ]] || fail "Missing $CONFIG_FILE"

  source "$CONFIG_FILE"

  : "${MC_VERSION:?MC_VERSION is required in server.env}"
  : "${PURPUR_BUILD:=latest}"
  : "${JAVA_CMD:=auto}"
  : "${MIN_MEMORY:=2G}"
  : "${MAX_MEMORY:=4G}"
  : "${SERVER_JAR:=server.jar}"

  JAR_PATH="$ROOT_DIR/$SERVER_JAR"
  resolve_java
}

resolve_java() {
  local candidate
  if [[ "$JAVA_CMD" != "auto" ]]; then
    return
  fi

  for candidate in \
    "$ROOT_DIR/runtime/java/bin/java" \
    "$ROOT_DIR/runtime/java/bin/java.exe"; do
    if [[ -x "$candidate" ]]; then
      JAVA_CMD="$candidate"
      return
    fi
  done
  JAVA_CMD="java"
}

java_major() {
  local first_line
  first_line="$("$JAVA_CMD" -version 2>&1 | head -n 1)" || return 1
  printf '%s\n' "$first_line" | sed -E 's/.*version "([0-9]+).*/\1/'
}

check_java() {
  local major
  if ! command -v "$JAVA_CMD" >/dev/null 2>&1; then
    printf 'Java: missing (%s)\n' "$JAVA_CMD"
    return 1
  fi

  major="$(java_major)" || {
    printf 'Java: unable to read version\n'
    return 1
  }

  if [[ ! "$major" =~ ^[0-9]+$ ]] || (( major < REQUIRED_JAVA_MAJOR )); then
    printf 'Java: version %s found; Java %s+ is required\n' "$major" "$REQUIRED_JAVA_MAJOR"
    return 1
  fi

  printf 'Java: version %s (OK)\n' "$major"
}

require_java() {
  check_java || fail "Install Java 25, then run this command again."
}

server_is_running() {
  local pid
  [[ -f "$LOCK_FILE" ]] || return 1
  pid="$(cat "$LOCK_FILE" 2>/dev/null || true)"

  if [[ "$pid" =~ ^[0-9]+$ ]] && kill -0 "$pid" 2>/dev/null; then
    return 0
  fi

  rm -f "$LOCK_FILE"
  return 1
}

require_server_stopped() {
  if server_is_running; then
    fail "The server appears to be running. Stop it before updating."
  fi
  return 0
}

download_file() {
  local url="$1"
  local destination="$2"

  if command -v curl >/dev/null 2>&1; then
    curl --fail --location --retry 3 --silent --show-error \
      --user-agent "$USER_AGENT" --output "$destination" "$url"
  elif command -v wget >/dev/null 2>&1; then
    wget --tries=3 --user-agent="$USER_AGENT" --output-document="$destination" "$url"
  else
    fail "Install curl or wget to download Purpur."
  fi
}

validate_jar() {
  local file="$1"
  local signature
  [[ -s "$file" ]] || fail "Downloaded file is empty."
  signature="$(od -An -tx1 -N4 "$file" | tr -d '[:space:]')"
  [[ "$signature" == "504b0304" ]] || fail "Downloaded file is not a valid JAR."
}

update_server() {
  local target_build="$PURPUR_BUILD"
  local url
  local temp_path="$JAR_PATH.download"
  local timestamp

  require_server_stopped
  mkdir -p "$ROOT_DIR/backups"
  rm -f "$temp_path"

  url="https://api.purpurmc.org/v2/purpur/$MC_VERSION/$target_build/download"
  printf 'Downloading Purpur %s build %s...\n' "$MC_VERSION" "$target_build"
  download_file "$url" "$temp_path"
  validate_jar "$temp_path"

  if [[ -f "$JAR_PATH" ]] && cmp -s "$JAR_PATH" "$temp_path"; then
    rm -f "$temp_path"
    printf 'Purpur is already up to date.\n'
    return
  fi

  if [[ -f "$JAR_PATH" ]]; then
    timestamp="$(date '+%Y%m%d-%H%M%S')"
    cp -p "$JAR_PATH" "$ROOT_DIR/backups/purpur-$MC_VERSION-$timestamp.jar"
  fi

  mv "$temp_path" "$JAR_PATH"
  {
    printf 'version=%s\n' "$MC_VERSION"
    printf 'build=%s\n' "$target_build"
    printf 'updated_at=%s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
  } > "$INSTALL_FILE"
  printf 'Installed %s\n' "$JAR_PATH"
}

eula_is_accepted() {
  grep -Eq '^[[:space:]]*eula=true[[:space:]]*$' "$ROOT_DIR/eula.txt"
}

check_server() {
  local status=0

  printf 'Minecraft: %s\n' "$MC_VERSION"
  printf 'Purpur build: %s\n' "$PURPUR_BUILD"
  printf 'Memory: %s to %s\n' "$MIN_MEMORY" "$MAX_MEMORY"

  check_java || status=1

  if [[ -f "$JAR_PATH" ]]; then
    printf 'Server JAR: present (%s)\n' "$SERVER_JAR"
  else
    printf 'Server JAR: missing (run: ./server.sh update)\n'
    status=1
  fi

  if eula_is_accepted; then
    printf 'EULA: accepted\n'
  else
    printf 'EULA: not accepted (edit eula.txt after reading the EULA)\n'
    status=1
  fi

  if server_is_running; then
    printf 'Server process: running\n'
  else
    printf 'Server process: stopped\n'
  fi

  return "$status"
}

start_server() {
  [[ -f "$JAR_PATH" ]] || update_server
  require_java
  eula_is_accepted || fail "Read https://aka.ms/MinecraftEULA and set eula=true in eula.txt."
  server_is_running && fail "The server appears to already be running."
  "$JAVA_CMD" "$ROOT_DIR/scripts/CustomPluginBuilder.java"
  "$JAVA_CMD" "$ROOT_DIR/scripts/WorldManager.java" inject

  printf '%s\n' "$$" > "$LOCK_FILE"
  trap 'rm -f "$LOCK_FILE"' EXIT INT TERM

  cd "$ROOT_DIR"
  printf 'Starting Purpur %s with %s to %s RAM...\n' "$MC_VERSION" "$MIN_MEMORY" "$MAX_MEMORY"
  "$JAVA_CMD" "-Xms$MIN_MEMORY" "-Xmx$MAX_MEMORY" -jar "$SERVER_JAR" --nogui
}

usage() {
  cat <<'EOF'
Usage: ./server.sh <command>

Commands:
  start   Start the server, downloading Purpur if needed
  update  Download the configured Purpur version/build safely
  check   Check Java, server JAR, EULA, and process status
EOF
}

load_config

case "${1:-start}" in
  start) start_server ;;
  update) update_server ;;
  check) check_server ;;
  *) usage; exit 2 ;;
esac
