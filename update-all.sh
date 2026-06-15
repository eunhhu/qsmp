#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"

bash "$ROOT_DIR/server.sh" update
bash "$ROOT_DIR/plugins.sh" resolve
bash "$ROOT_DIR/plugins.sh" update
