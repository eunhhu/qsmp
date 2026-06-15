#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TEMP_DIR"' EXIT

cat > "$TEMP_DIR/curl" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

destination=""
while (($#)); do
  if [[ "$1" == "--output" ]]; then
    destination="$2"
    shift 2
  else
    shift
  fi
done

cp "$QSMP_TEST_JAR" "$destination"
EOF
chmod +x "$TEMP_DIR/curl"

output="$(
  PATH="$TEMP_DIR:$PATH" QSMP_TEST_JAR="$ROOT_DIR/server.jar" \
    bash "$ROOT_DIR/server.sh" update
)"

grep -Fq "Purpur is already up to date." <<< "$output"
printf 'server.sh stopped-state update regression: PASS\n'
