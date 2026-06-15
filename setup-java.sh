#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
RUNTIME_DIR="$ROOT_DIR/runtime"
JAVA_DIR="$RUNTIME_DIR/java"
STAGE_DIR="$RUNTIME_DIR/java-stage"
USER_AGENT="qsmp-runtime-manager/1.0"

safe_remove() {
  local target="$1"
  case "$target" in
    "$RUNTIME_DIR"/*) rm -rf -- "$target" ;;
    *) printf 'Error: Refusing to remove path outside runtime: %s\n' "$target" >&2; exit 1 ;;
  esac
}

if [[ -x "$JAVA_DIR/bin/java" ]]; then
  existing_version="$("$JAVA_DIR/bin/java" --version | head -n 1)"
  existing_major="$(printf '%s\n' "$existing_version" | sed -E 's/^(openjdk|java) ([0-9]+).*/\2/')"
  if [[ "$existing_major" =~ ^[0-9]+$ ]] && (( existing_major >= 25 )); then
    printf 'Portable Java is already installed: %s\n' "$existing_version"
    exit 0
  fi
fi

case "$(uname -s)" in
  Linux*) os="linux"; extension="tar.gz" ;;
  Darwin*) os="mac"; extension="tar.gz" ;;
  MINGW*|MSYS*|CYGWIN*) os="windows"; extension="zip" ;;
  *) printf 'Error: Unsupported operating system.\n' >&2; exit 1 ;;
esac

case "$(uname -m)" in
  x86_64|amd64) arch="x64" ;;
  arm64|aarch64) arch="aarch64" ;;
  *) printf 'Error: Unsupported architecture: %s\n' "$(uname -m)" >&2; exit 1 ;;
esac

mkdir -p "$RUNTIME_DIR"
safe_remove "$STAGE_DIR"
archive="$RUNTIME_DIR/temurin-25.$extension"
rm -f -- "$archive"
api_url="https://api.adoptium.net/v3/binary/latest/25/ga/$os/$arch/jdk/hotspot/normal/eclipse"

printf 'Downloading Eclipse Temurin Java 25...\n'
effective_url="$(curl --fail --location --silent --show-error \
  --user-agent "$USER_AGENT" --output "$archive" --write-out '%{url_effective}' "$api_url")"
case "$effective_url" in
  https://github.com/adoptium/temurin25-binaries/*) ;;
  *) printf 'Error: Unexpected Java download host.\n' >&2; exit 1 ;;
esac

expected_hash="$(curl --fail --location --silent --show-error \
  --user-agent "$USER_AGENT" "$effective_url.sha256.txt" | awk '{print $1}')"
if command -v sha256sum >/dev/null 2>&1; then
  actual_hash="$(sha256sum "$archive" | awk '{print $1}')"
else
  actual_hash="$(shasum -a 256 "$archive" | awk '{print $1}')"
fi
[[ "$actual_hash" == "$expected_hash" ]] || {
  printf 'Error: Java archive SHA-256 verification failed.\n' >&2
  exit 1
}

mkdir -p "$STAGE_DIR"
if [[ "$extension" == "zip" ]]; then
  unzip -q "$archive" -d "$STAGE_DIR"
else
  tar -xzf "$archive" -C "$STAGE_DIR"
fi

extracted="$(find "$STAGE_DIR" -mindepth 1 -maxdepth 1 -type d | head -n 1)"
[[ -n "$extracted" ]] || {
  printf 'Error: Extracted Java runtime was not found.\n' >&2
  exit 1
}

candidate="$RUNTIME_DIR/java-new"
safe_remove "$candidate"
mv "$extracted" "$candidate"
candidate_java="$candidate/bin/java"
[[ "$os" == "windows" ]] && candidate_java="$candidate/bin/java.exe"
candidate_version="$("$candidate_java" --version | head -n 1)"
candidate_major="$(printf '%s\n' "$candidate_version" | sed -E 's/^(openjdk|java) ([0-9]+).*/\2/')"
[[ "$candidate_major" =~ ^[0-9]+$ ]] && (( candidate_major >= 25 )) || {
  printf 'Error: Downloaded runtime is not Java 25 or newer.\n' >&2
  exit 1
}

safe_remove "$JAVA_DIR"
mv "$candidate" "$JAVA_DIR"
safe_remove "$STAGE_DIR"
rm -f -- "$archive"
printf 'Installed %s in %s\n' "$candidate_version" "$JAVA_DIR"
