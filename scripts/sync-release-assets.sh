#!/bin/bash
# Sync the latest stable server archive from the launcher mirror.
# Verify the manifest hash before replacing assets used by cosmetics and downloads.
set -euo pipefail

AUTH_ROOT="${AUTH_ROOT:-/var/www/traefik}"
MIRROR_URL="${MIRROR_URL:-https://dl1.htdwnldsan.top}"
ASSETS_PATH="${ASSETS_PATH:-$AUTH_ROOT/hytale-assets/Assets.zip}"
DOWNLOADS_DIR="${DOWNLOADS_DIR:-$AUTH_ROOT/hytale-auth-data/downloads}"
STATE_DIR="${STATE_DIR:-$AUTH_ROOT/hytale-auth-data/release-sync}"
COMPOSE_DIR="${COMPOSE_DIR:-$AUTH_ROOT}"
AUTH_SERVICE="${AUTH_SERVICE:-hytale-auth}"
RESTART_AUTH="${RESTART_AUTH:-1}"
LOCK_FILE="${LOCK_FILE:-/tmp/sync-release-assets.lock}"
LOG_PREFIX="[sync-release-assets]"

exec 9>"$LOCK_FILE"
if ! flock -n 9; then
    echo "$LOG_PREFIX another sync is already running"
    exit 0
fi

mkdir -p "$(dirname "$ASSETS_PATH")" "$DOWNLOADS_DIR" "$STATE_DIR"

echo "$LOG_PREFIX starting at $(date)"

manifest=$(curl -fsSL --connect-timeout 15 --max-time 120 "$MIRROR_URL/manifest.json")
release_info=$(printf '%s' "$manifest" | python3 -c '
import json, re, sys
entries = []
for key, meta in json.load(sys.stdin)["files"].items():
    match = re.fullmatch(r"server/release/(\d+\.\d+\.\d+)\.zip", key)
    if match:
        entries.append((tuple(map(int, match[1].split("."))), match[1], key, meta))
_, version, key, meta = max(entries)
sha = meta.get("actualSha256") or meta.get("sha256", "")
if not re.fullmatch(r"[0-9a-fA-F]{64}", sha):
    sys.exit("Latest release is missing a valid SHA-256")
print(version, sha.lower(), key, sep="\t")
')
IFS=$'\t' read -r version sha256 archive_key <<< "$release_info"

current_version="$(cat "$STATE_DIR/release.version" 2>/dev/null || true)"
current_sha="$(cat "$STATE_DIR/release.sha256" 2>/dev/null || true)"

if [ "$current_version" = "$version" ] && [ "$current_sha" = "$sha256" ] \
    && [ -f "$ASSETS_PATH" ] && [ -f "$DOWNLOADS_DIR/HytaleServer.jar" ]; then
    echo "$LOG_PREFIX release $version already installed"
    exit 0
fi

echo "$LOG_PREFIX downloading release $version from $MIRROR_URL"
archive=$(mktemp "$STATE_DIR/server-XXXXXX.zip")
trap 'rm -f "$archive" "$ASSETS_PATH.tmp" "$DOWNLOADS_DIR/HytaleServer.jar.tmp"' EXIT
curl -fsSL --retry 3 --connect-timeout 15 --max-time 3600 \
    "$MIRROR_URL/$archive_key" -o "$archive"
printf '%s  %s\n' "$sha256" "$archive" | sha256sum -c -
unzip -p "$archive" Assets.zip > "$ASSETS_PATH.tmp"
unzip -p "$archive" Server/HytaleServer.jar > "$DOWNLOADS_DIR/HytaleServer.jar.tmp"
test -s "$ASSETS_PATH.tmp"
test -s "$DOWNLOADS_DIR/HytaleServer.jar.tmp"
# Check nested archives before publishing either file.
unzip -tq "$ASSETS_PATH.tmp" >/dev/null
unzip -tq "$DOWNLOADS_DIR/HytaleServer.jar.tmp" >/dev/null
chmod 0644 "$ASSETS_PATH.tmp" "$DOWNLOADS_DIR/HytaleServer.jar.tmp"
mv "$ASSETS_PATH.tmp" "$ASSETS_PATH"
mv "$DOWNLOADS_DIR/HytaleServer.jar.tmp" "$DOWNLOADS_DIR/HytaleServer.jar"

if [ "$RESTART_AUTH" = "1" ]; then
    test -f "$COMPOSE_DIR/compose.yaml"
    (cd "$COMPOSE_DIR" && docker compose restart "$AUTH_SERVICE")
    echo "$LOG_PREFIX restarted $AUTH_SERVICE"
fi

# Record success only after the restart, so a failed reload is retried.
printf '%s\n' "$version" > "$STATE_DIR/release.version"
printf '%s\n' "$sha256" > "$STATE_DIR/release.sha256"

jar_mb=$(( $(stat -c%s "$DOWNLOADS_DIR/HytaleServer.jar" 2>/dev/null || stat -f%z "$DOWNLOADS_DIR/HytaleServer.jar") / 1024 / 1024 ))
assets_mb=$(( $(stat -c%s "$ASSETS_PATH" 2>/dev/null || stat -f%z "$ASSETS_PATH") / 1024 / 1024 ))
echo "$LOG_PREFIX installed release $version: HytaleServer.jar ${jar_mb}MB, Assets.zip ${assets_mb}MB"
echo "$LOG_PREFIX finished at $(date)"
