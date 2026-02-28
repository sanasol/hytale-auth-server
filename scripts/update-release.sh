
#!/bin/bash
# Hytale Server Release Automation Script
# Automates: download -> upload to Mega S4 -> update CDN links on auth server
#
# No patching needed - DualAuth ByteBuddy Agent is applied at runtime via -javaagent:
# Agent is distributed separately via GitHub releases.
#
# Prerequisites:
#   1. Install rclone: brew install rclone
#   2. Configure rclone for MEGA S4 (see setup instructions below)
#   3. Set ADMIN_PASSWORD environment variable

set -e

# Configuration
HYTALE_DOCKER_DIR="/Users/sanasol/code/hytale-docker"
STAGING_DIR="/Users/sanasol/code/pterodactyl-hytale/hytale-auth-server/release-staging"
ASSETS_DIR="/Users/sanasol/code/pterodactyl-hytale/hytale-auth-server/assets"

# MEGA S4 Configuration
RCLONE_REMOTE="megas4"                    # rclone remote name
S4_ACCOUNT_HASH="kcvismkrtfcalgwxzsazbq46l72dwsypqaham"  # Your S4 account hash (in URL)
S4_BUCKET="hytale"                        # Your S4 bucket name
S4_ENDPOINT="s3.g.s4.mega.io"            # MEGA S4 global endpoint

AUTH_SERVER="https://auth.sanasol.ws"
ADMIN_TOKEN="${ADMIN_TOKEN:-}"

PORTAL_URL="${PORTAL_URL:-https://hytale.sanhost.net}"
PORTAL_KEY="${PORTAL_KEY:-${INTERNAL_API_KEY:-}}"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

log() { printf "%b[%s]%b %b\n" "${GREEN}" "$(date +%H:%M:%S)" "${NC}" "$1"; }
warn() { printf "%b[%s]%b %b\n" "${YELLOW}" "$(date +%H:%M:%S)" "${NC}" "$1"; }
error() { printf "%b[%s]%b %b\n" "${RED}" "$(date +%H:%M:%S)" "${NC}" "$1"; exit 1; }

# Check dependencies
check_deps() {
    log "Checking dependencies..."

    if ! command -v rclone &> /dev/null; then
        error "rclone not installed. Install with: brew install rclone"
    fi

    # Check rclone remote exists
    if ! rclone listremotes | grep -q "^${RCLONE_REMOTE}:"; then
        echo ""
        error "rclone remote '${RCLONE_REMOTE}' not configured. Run setup first (see --setup)"
    fi

    log "All dependencies OK"
}

# Setup rclone for MEGA S4
setup_rclone() {
    echo ""
    echo "=========================================="
    echo "  MEGA S4 Rclone Setup"
    echo "=========================================="
    echo ""
    echo "You need your MEGA S4 credentials:"
    echo "  - Access Key ID"
    echo "  - Secret Access Key"
    echo ""
    echo "Get these from: https://mega.io/objectstorage"
    echo "(Settings → S4 → Access Keys)"
    echo ""

    read -p "Enter your S4 Access Key ID: " ACCESS_KEY
    read -sp "Enter your S4 Secret Access Key: " SECRET_KEY
    echo ""

    # Create rclone config
    cat >> ~/.config/rclone/rclone.conf << EOF

[${RCLONE_REMOTE}]
type = s3
provider = Other
access_key_id = ${ACCESS_KEY}
secret_access_key = ${SECRET_KEY}
endpoint = ${S4_ENDPOINT}
acl = public-read
EOF

    log "rclone configured! Testing connection..."

    if rclone lsd "${RCLONE_REMOTE}:" &>/dev/null; then
        log "Connection successful!"
        rclone lsd "${RCLONE_REMOTE}:"
    else
        error "Connection failed. Check your credentials."
    fi
}

# Refresh downloader credentials via portal API
refresh_credentials() {
    local creds_file="$HYTALE_DOCKER_DIR/data/.hytale-downloader-credentials.json"

    if [ -z "$PORTAL_KEY" ]; then
        warn "PORTAL_KEY (or INTERNAL_API_KEY) not set — skipping auto-refresh"
        return 1
    fi

    # Check if token is still valid
    if [ -f "$creds_file" ]; then
        local expires_at now
        expires_at=$(python3 -c "import json; print(json.load(open('$creds_file')).get('expires_at', 0))" 2>/dev/null || echo 0)
        now=$(date +%s)
        if [ "$now" -lt "$((expires_at - 60))" ]; then
            log "Credentials still valid ($(( (expires_at - now) / 60 )) min remaining)"
            return 0
        fi
    fi

    log "Fetching fresh downloader credentials from portal..."

    local response
    response=$(curl -sf --max-time 120 \
        -H "X-Internal-Key: ${PORTAL_KEY}" \
        "${PORTAL_URL}/api/internal/oauth/token?client=hytale-downloader" 2>&1)

    if [ $? -ne 0 ] || [ -z "$response" ]; then
        warn "Portal request failed: $response"
        return 1
    fi

    # Verify response has access_token
    if ! echo "$response" | python3 -c "import sys,json; d=json.load(sys.stdin); assert d.get('access_token')" 2>/dev/null; then
        warn "Portal returned invalid response: $(echo "$response" | head -c 200)"
        return 1
    fi

    # Write credentials file
    echo "$response" > "$creds_file"
    log "Credentials refreshed successfully!"
    return 0
}

# Extract version from JAR
get_version() {
    local jar="$1"
    unzip -p "$jar" META-INF/MANIFEST.MF | grep "Implementation-Version" | cut -d' ' -f2 | tr -d '\r\n'
}

# Check latest available version
check_latest_version() {
    log "Checking latest available version..."

    # Auto-refresh credentials if expired
    refresh_credentials || true

    # Check if credentials exist
    if [ ! -f "$HYTALE_DOCKER_DIR/data/.hytale-downloader-credentials.json" ]; then
        error "Credentials not found. Run the official Hytale downloader to login first."
    fi

    LATEST_VERSION=$(docker run --rm \
        -v "$HYTALE_DOCKER_DIR/data:/data" \
        alpine /data/.hytale-downloader/hytale-downloader \
        -credentials-path /data/.hytale-downloader-credentials.json \
        -print-version 2>&1)

    # Check for auth errors
    if echo "$LATEST_VERSION" | grep -qi "unauthorized\|expired\|invalid"; then
        echo ""
        warn "Authentication failed even after refresh attempt."
        warn "Ensure PORTAL_KEY is set for auto-refresh via portal."
        warn "Or re-authenticate manually:"
        warn "  cd $HYTALE_DOCKER_DIR && docker compose run --rm hytale"
        error "Please re-authenticate and try again."
    fi

    if [ -z "$LATEST_VERSION" ] || ! echo "$LATEST_VERSION" | grep -qE '^[0-9]{4}\.[0-9]{2}\.[0-9]{2}'; then
        error "Failed to check latest version. Response: $LATEST_VERSION"
    fi

    log "Latest available: ${BLUE}$LATEST_VERSION${NC}"

    # Check current version
    if [ -f "$HYTALE_DOCKER_DIR/data/server/HytaleServer.jar" ]; then
        CURRENT_VERSION=$(get_version "$HYTALE_DOCKER_DIR/data/server/HytaleServer.jar")
        log "Current version:  ${BLUE}$CURRENT_VERSION${NC}"

        if [ "$CURRENT_VERSION" = "$LATEST_VERSION" ]; then
            log "Already up to date!"
            return 1
        fi
    fi

    return 0
}

# Step 1: Download latest files using hytale-downloader
download_latest() {
    log "Downloading latest Hytale server..."

    # Ensure credentials are fresh before download
    refresh_credentials || true

    # Run downloader in container
    docker run --rm \
        -v "$HYTALE_DOCKER_DIR/data:/data" \
        alpine /data/.hytale-downloader/hytale-downloader \
        -credentials-path /data/.hytale-downloader-credentials.json \
        -download-path /data/download.zip \
        -skip-update-check

    if [ $? -ne 0 ]; then
        error "Download failed"
    fi

    # Extract the download
    log "Extracting downloaded files..."
    cd "$HYTALE_DOCKER_DIR/data"
    unzip -o download.zip -d . 2>/dev/null || true
    rm -f download.zip

    # Verify files
    if [ ! -f "$HYTALE_DOCKER_DIR/data/server/HytaleServer.jar" ] && [ ! -f "$HYTALE_DOCKER_DIR/data/HytaleServer.jar" ]; then
        # Check if JAR is in root
        if [ -f "$HYTALE_DOCKER_DIR/data/HytaleServer.jar" ]; then
            mkdir -p "$HYTALE_DOCKER_DIR/data/server"
            mv "$HYTALE_DOCKER_DIR/data/HytaleServer.jar" "$HYTALE_DOCKER_DIR/data/server/"
        else
            error "HytaleServer.jar not found after download"
        fi
    fi

    if [ ! -f "$HYTALE_DOCKER_DIR/data/Assets.zip" ]; then
        error "Assets.zip not found after download"
    fi

    log "Download complete!"
}

# Step 2: Stage files and extract version
prepare_files() {
    log "Staging files for upload..."

    mkdir -p "$STAGING_DIR"

    cp "$HYTALE_DOCKER_DIR/data/server/HytaleServer.jar" "$STAGING_DIR/HytaleServer.jar"
    cp "$HYTALE_DOCKER_DIR/data/Assets.zip" "$ASSETS_DIR/Assets.zip"

    VERSION=$(get_version "$STAGING_DIR/HytaleServer.jar")

    if [ -z "$VERSION" ]; then
        error "Could not extract version from JAR"
    fi

    log "Detected version: ${BLUE}$VERSION${NC}"
    export VERSION
}

# Step 3: Create versioned files
create_versioned_files() {
    log "Creating versioned files..."

    cp "$STAGING_DIR/HytaleServer.jar" "$STAGING_DIR/HytaleServer-${VERSION}.jar"
    cp "$ASSETS_DIR/Assets.zip" "$ASSETS_DIR/Assets-${VERSION}.zip"

    log "Created files:"
    ls -lh "$STAGING_DIR/HytaleServer-${VERSION}.jar"
    ls -lh "$ASSETS_DIR/Assets-${VERSION}.zip"
}

# Step 4: Upload to MEGA S4
upload_to_s4() {
    log "Uploading to MEGA S4..."

    local remote_path="${RCLONE_REMOTE}:${S4_BUCKET}"

    # Upload server JAR (raw, unpatched - agent handles auth at runtime)
    log "Uploading HytaleServer.jar..."
    rclone copy "$STAGING_DIR/HytaleServer-${VERSION}.jar" "$remote_path/" --progress

    # Upload assets (this takes a while - ~3.3GB)
    log "Uploading Assets.zip (this may take a while - ~3.3GB)..."
    rclone copy "$ASSETS_DIR/Assets-${VERSION}.zip" "$remote_path/" --progress

    log "Upload complete!"

    # Generate public URLs (format: endpoint/account-hash/bucket/file)
    # Note: Bucket must have "Grant object URL access" enabled in MEGA S4 dashboard
    SERVER_URL="https://${S4_ENDPOINT}/${S4_ACCOUNT_HASH}/${S4_BUCKET}/HytaleServer-${VERSION}.jar"
    ASSETS_URL="https://${S4_ENDPOINT}/${S4_ACCOUNT_HASH}/${S4_BUCKET}/Assets-${VERSION}.zip"

    echo ""
    log "Public URLs:"
    echo "  Server:  $SERVER_URL"
    echo "  Assets:  $ASSETS_URL"

    export SERVER_URL ASSETS_URL
}

# Step 5: Update CDN links via admin API
update_cdn_links() {
    if [ -z "$ADMIN_TOKEN" ]; then
        warn "ADMIN_TOKEN not set. Skipping automatic CDN update."
        echo ""
        echo "To update manually, go to: ${AUTH_SERVER}/admin/page/settings"
        echo "Or set ADMIN_TOKEN and run: $0 --update-links-only"
        return
    fi

    log "Updating CDN links on auth server..."

    # HytaleServer.jar -> raw server (agent patches at runtime)
    # Assets.zip -> game assets
    local response
    response=$(curl -s -X POST "${AUTH_SERVER}/admin/api/settings/downloads" \
        -H "Content-Type: application/json" \
        -H "X-Admin-Token: ${ADMIN_TOKEN}" \
        -d "{
            \"links\": {
                \"HytaleServer.jar\": \"${SERVER_URL}\",
                \"Assets.zip\": \"${ASSETS_URL}\"
            }
        }")

    if echo "$response" | grep -q '"success":true'; then
        log "CDN links updated successfully!"
        log "  HytaleServer.jar -> ${SERVER_URL}"
        log "  Assets.zip       -> ${ASSETS_URL}"
    else
        warn "Failed to update CDN links: $response"
        echo ""
        echo "Update manually at: ${AUTH_SERVER}/admin/page/settings"
    fi
}

# Verify uploaded files
verify_uploads() {
    log "Verifying uploaded files..."

    local errors=0

    for url in "$SERVER_URL" "$ASSETS_URL"; do
        # Use GET with range header (MEGA S4 returns 400 for HEAD requests)
        local status=$(curl -s -o /dev/null -w "%{http_code}" -r 0-0 "$url")
        if [ "$status" = "200" ] || [ "$status" = "206" ]; then
            printf "  ✓ %s\n" "$(basename "$url")"
        else
            printf "  ✗ %s (HTTP %s)\n" "$(basename "$url")" "$status"
            errors=$((errors + 1))
        fi
    done

    if [ $errors -gt 0 ]; then
        warn "$errors file(s) not accessible. Check ACL settings."
    else
        log "All files verified and publicly accessible!"
    fi
}

# Summary
show_summary() {
    echo ""
    echo "========================================"
    echo -e "${GREEN}Release Update Complete!${NC}"
    echo "========================================"
    echo ""
    echo "Version: $VERSION"
    echo ""
    echo "Files uploaded to MEGA S4:"
    echo "  - HytaleServer-${VERSION}.jar (raw - agent patches at runtime)"
    echo "  - Assets-${VERSION}.zip"
    echo ""
    echo "Public URLs:"
    echo "  $SERVER_URL"
    echo "  $ASSETS_URL"
    echo ""
    echo "DualAuth Agent: distributed via GitHub releases (not bundled with server)"
    echo "  https://github.com/sanasol/hytale-auth-server/releases/latest/download/dualauth-agent.jar"
    echo ""
    if [ -n "$ADMIN_TOKEN" ]; then
        echo "CDN links updated automatically on ${AUTH_SERVER}"
    else
        echo "Update CDN links at: ${AUTH_SERVER}/admin/page/settings"
    fi
    echo ""
}

# Show help
show_help() {
    echo "Hytale Server Release Automation"
    echo ""
    echo "Downloads latest Hytale server, uploads to MEGA S4, and updates CDN links."
    echo "No patching needed - DualAuth ByteBuddy Agent is applied at runtime."
    echo ""
    echo "Usage: $0 [options]"
    echo ""
    echo "Options:"
    echo "  --check              Check for new version only (no download)"
    echo "  --setup              Configure rclone for MEGA S4"
    echo "  --skip-download      Skip download (use existing files)"
    echo "  --skip-upload        Skip MEGA S4 upload"
    echo "  --update-links-only  Only update CDN links (requires --version)"
    echo "  --version VERSION    Specify version manually"
    echo "  --help               Show this help"
    echo ""
    echo "Environment variables:"
    echo "  ADMIN_TOKEN          Auth server admin token (for auto CDN update)"
    echo "  PORTAL_KEY           Portal internal API key (for auto credential refresh)"
    echo "  PORTAL_URL           Portal URL (default: https://hytale.sanhost.net)"
    echo ""
    echo "Examples:"
    echo "  $0 --check                              # Check if new version available"
    echo "  $0                                       # Full automation"
    echo "  $0 --skip-download                       # Use existing downloaded files"
    echo "  ADMIN_TOKEN=xxx $0                       # Full run with auto CDN update"
    echo "  $0 --update-links-only --version 2026.02.17-abc123  # Just update links"
    echo ""
    echo "First-time setup:"
    echo "  1. Install rclone: brew install rclone"
    echo "  2. Run: $0 --setup"
    echo "  3. Run: export ADMIN_TOKEN='your-token'"
    echo "  4. Run: $0"
    echo ""
}

# Main
main() {
    # Parse arguments
    SKIP_DOWNLOAD=false
    SKIP_UPLOAD=false
    UPDATE_LINKS_ONLY=false
    DO_SETUP=false
    CHECK_ONLY=false

    while [[ $# -gt 0 ]]; do
        case $1 in
            --check) CHECK_ONLY=true; shift ;;
            --setup) DO_SETUP=true; shift ;;
            --skip-download) SKIP_DOWNLOAD=true; shift ;;
            --skip-upload) SKIP_UPLOAD=true; shift ;;
            --update-links-only) UPDATE_LINKS_ONLY=true; shift ;;
            --version) VERSION="$2"; shift 2 ;;
            --help) show_help; exit 0 ;;
            *) shift ;;
        esac
    done

    if [ "$DO_SETUP" = true ]; then
        setup_rclone
        exit 0
    fi

    if [ "$CHECK_ONLY" = true ]; then
        echo ""
        echo "========================================"
        echo "  Checking for Hytale Updates"
        echo "========================================"
        echo ""
        if check_latest_version; then
            log "New version available! Run without --check to update."
            exit 0
        else
            exit 0
        fi
    fi

    echo ""
    echo "========================================"
    echo "  Hytale Server Release Automation"
    echo "========================================"
    echo ""

    check_deps

    if [ "$UPDATE_LINKS_ONLY" = true ]; then
        if [ -z "$VERSION" ]; then
            error "VERSION required for --update-links-only. Use --version VERSION"
        fi
        SERVER_URL="https://${S4_ENDPOINT}/${S4_ACCOUNT_HASH}/${S4_BUCKET}/HytaleServer-${VERSION}.jar"
        ASSETS_URL="https://${S4_ENDPOINT}/${S4_ACCOUNT_HASH}/${S4_BUCKET}/Assets-${VERSION}.zip"
        update_cdn_links
        exit 0
    fi

    if [ "$SKIP_DOWNLOAD" = false ]; then
        download_latest
    else
        log "Skipping download (using existing files)"
    fi

    prepare_files
    create_versioned_files

    if [ "$SKIP_UPLOAD" = false ]; then
        upload_to_s4
        verify_uploads
    else
        log "Skipping MEGA S4 upload"
        # Still set URLs for CDN update
        SERVER_URL="https://${S4_ENDPOINT}/${S4_ACCOUNT_HASH}/${S4_BUCKET}/HytaleServer-${VERSION}.jar"
        ASSETS_URL="https://${S4_ENDPOINT}/${S4_ACCOUNT_HASH}/${S4_BUCKET}/Assets-${VERSION}.zip"
    fi

    update_cdn_links
    show_summary
}

main "$@"
