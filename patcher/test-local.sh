#!/bin/bash
# Local test script for DualAuth Patcher
# Tests patcher compilation, patching, server boot, and client connections
#
# Usage: ./test-local.sh [--skip-download] [--skip-client]
#
# Requirements:
# - Java 21+
# - curl
# - unzip
# - python3

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

# Find Java 21+ (required for Hytale server)
find_java21() {
    local java_version java_path java_home_path

    # Check JAVA_HOME first
    if [ -n "$JAVA_HOME" ]; then
        java_version=$("$JAVA_HOME/bin/java" -version 2>&1 | head -1 | grep -oE '[0-9]+' | head -1)
        if [ "$java_version" -ge 21 ] 2>/dev/null; then
            echo "$JAVA_HOME/bin/java"
            return 0
        fi
    fi

    # Check Homebrew paths (macOS)
    for version in 25 24 23 22 21; do
        for base in /opt/homebrew/opt /usr/local/opt; do
            java_path="$base/openjdk@$version/bin/java"
            if [ -x "$java_path" ]; then
                echo "$java_path"
                return 0
            fi
        done
    done

    # Check standard macOS java_home
    if command -v /usr/libexec/java_home >/dev/null 2>&1; then
        for version in 21 22 23 24 25; do
            java_home_path=$(/usr/libexec/java_home -v "$version" 2>/dev/null) || continue
            if [ -n "$java_home_path" ] && [ -x "$java_home_path/bin/java" ]; then
                echo "$java_home_path/bin/java"
                return 0
            fi
        done
    fi

    # Check PATH
    if command -v java >/dev/null 2>&1; then
        java_version=$(java -version 2>&1 | head -1 | grep -oE '[0-9]+' | head -1)
        if [ "$java_version" -ge 21 ] 2>/dev/null; then
            echo "java"
            return 0
        fi
    fi

    return 1
}

# Find Java
JAVA_CMD=$(find_java21) || {
    echo "ERROR: Java 21+ not found!"
    echo ""
    echo "Install with Homebrew:"
    echo "  brew install openjdk@21"
    echo ""
    echo "Or set JAVA_HOME to your Java 21+ installation"
    exit 1
}

echo "Using Java: $JAVA_CMD"
$JAVA_CMD -version 2>&1 | head -1

# Derive javac path from java path
JAVA_BIN_DIR=$(dirname "$JAVA_CMD")
JAVAC_CMD="$JAVA_BIN_DIR/javac"
if [ ! -x "$JAVAC_CMD" ]; then
    JAVAC_CMD="javac"  # Fallback to PATH
fi
echo "Using javac: $JAVAC_CMD"
echo ""

# Configuration
HYTALE_AUTH_URL="${HYTALE_AUTH_URL:-https://auth.sanasol.ws}"
HYTALE_AUTH_DOMAIN="${HYTALE_AUTH_DOMAIN:-auth.sanasol.ws}"
SERVER_JAR_URL="${SERVER_JAR_URL:-https://download.sanasol.ws/download/HytaleServerOriginal.jar}"
ASSETS_URL="${ASSETS_URL:-https://download.sanasol.ws/download/Assets.zip}"

SKIP_CLIENT=false
FORCE_DOWNLOAD=false
TOKEN_MODE="both"  # with-tokens, no-tokens, both

# Parse arguments
for arg in "$@"; do
    case $arg in
        --skip-client) SKIP_CLIENT=true ;;
        --force-download) FORCE_DOWNLOAD=true ;;
        --token-mode=*)
            TOKEN_MODE="${arg#*=}"
            case "$TOKEN_MODE" in
                with-tokens|no-tokens|both) ;;
                *)
                    echo "ERROR: Invalid token mode '$TOKEN_MODE'"
                    echo "Valid options: with-tokens, no-tokens, both"
                    exit 1
                    ;;
            esac
            ;;
        --help|-h)
            echo "Usage: $0 [options]"
            echo ""
            echo "Tests DualAuth patcher compilation, patching, and server boot."
            echo ""
            echo "Options:"
            echo "  --skip-client          Skip client connection tests"
            echo "  --force-download       Force re-download of JAR and Assets"
            echo "  --token-mode=MODE      Token mode: with-tokens, no-tokens, both (default: both)"
            echo "  --help, -h             Show this help"
            echo ""
            echo "Token modes:"
            echo "  with-tokens   Start server with explicit --session-token and --identity-token"
            echo "  no-tokens     Start server without tokens (auto-fetch mode)"
            echo "  both          Run both scenarios (default)"
            echo ""
            echo "Files are cached automatically:"
            echo "  - HytaleServerOriginal.jar (reused if exists)"
            echo "  - Assets.zip (reused if exists, 3+ GB)"
            echo ""
            echo "Environment variables:"
            echo "  HYTALE_AUTH_URL    Auth server URL (default: https://auth.sanasol.ws)"
            echo "  SERVER_JAR_URL     Server JAR download URL"
            echo "  ASSETS_URL         Assets.zip download URL"
            exit 0
            ;;
    esac
done

echo "Token mode: $TOKEN_MODE"

echo "=============================================="
echo "  DualAuth Patcher - Local Test"
echo "=============================================="
echo ""
echo "Auth Server: $HYTALE_AUTH_URL"
echo "Auth Domain: $HYTALE_AUTH_DOMAIN"
echo ""

# Step 1: Download ASM libraries if needed
echo "=== Step 1: Check ASM Libraries ==="
if [ ! -f "lib/asm-9.6.jar" ]; then
    echo "Downloading ASM libraries..."
    mkdir -p lib
    cd lib
    curl -sfLO https://repo1.maven.org/maven2/org/ow2/asm/asm/9.6/asm-9.6.jar
    curl -sfLO https://repo1.maven.org/maven2/org/ow2/asm/asm-tree/9.6/asm-tree-9.6.jar
    curl -sfLO https://repo1.maven.org/maven2/org/ow2/asm/asm-util/9.6/asm-util-9.6.jar
    cd ..
    echo "ASM libraries downloaded"
else
    echo "ASM libraries already present"
fi
echo ""

# Step 2: Compile patcher
echo "=== Step 2: Compile Patcher ==="
rm -f DualAuthPatcher*.class 2>/dev/null
"$JAVAC_CMD" -cp "lib/*" DualAuthPatcher.java
echo "Compilation successful"
ls -la DualAuthPatcher*.class
echo ""

# Step 3: Download server JAR
echo "=== Step 3: Download Server JAR ==="
if [ -f "HytaleServerOriginal.jar" ] && [ "$FORCE_DOWNLOAD" = false ]; then
    echo "Using cached HytaleServerOriginal.jar"
    ls -lh HytaleServerOriginal.jar
else
    echo "Downloading from: $SERVER_JAR_URL"
    curl -sfL "$SERVER_JAR_URL" -o HytaleServerOriginal.jar
    ls -lh HytaleServerOriginal.jar

    # Check if already patched
    if unzip -l HytaleServerOriginal.jar 2>/dev/null | grep -q "DualAuthContext.class"; then
        echo "WARNING: Downloaded JAR appears to already be patched"
    fi
fi
echo ""

# Step 4: Run patcher
echo "=== Step 4: Run Patcher ==="
rm -f HytaleServerPatched.jar 2>/dev/null
"$JAVA_CMD" -cp ".:lib/*" DualAuthPatcher HytaleServerOriginal.jar HytaleServerPatched.jar

echo ""
echo "Verifying patched JAR..."
DUAL_CLASSES=$(unzip -l HytaleServerPatched.jar 2>/dev/null | grep -c "DualAuth\|DualJwks\|DualServer" || echo "0")
echo "DualAuth classes found: $DUAL_CLASSES"

if [ "$DUAL_CLASSES" -lt 5 ]; then
    echo "ERROR: Patching failed - expected at least 5 DualAuth classes"
    exit 1
fi

unzip -l HytaleServerPatched.jar | grep -E "DualAuth|DualJwks|DualServer"
echo ""
echo "Patching successful!"
echo ""

# Step 5: Download Assets
echo "=== Step 5: Download Assets ==="
if [ -f "Assets.zip" ] && [ "$FORCE_DOWNLOAD" = false ]; then
    echo "Using cached Assets.zip"
    ls -lh Assets.zip
else
    echo "Downloading from: $ASSETS_URL"
    echo "This may take a while (3+ GB)..."
    curl -sfL "$ASSETS_URL" -o Assets.zip
    ls -lh Assets.zip
fi
echo ""

# Step 6: Verify auth server and get tokens
echo "=== Step 6: Verify Auth Server & Get Tokens ==="
echo "Checking $HYTALE_AUTH_URL..."

if ! curl -sf "$HYTALE_AUTH_URL/health" >/dev/null 2>&1; then
    echo "WARNING: Auth server health check failed, continuing anyway..."
else
    echo "Auth server is healthy"
fi

echo "Fetching JWKS..."
curl -sf "$HYTALE_AUTH_URL/.well-known/jwks.json" | head -5
echo ""

# Get server tokens
echo "Fetching server tokens..."
SERVER_ID="local-test-$(date +%s)"
SERVER_NAME="Local Test Server"

SERVER_RESPONSE=$(curl -sf -X POST "$HYTALE_AUTH_URL/server/auto-auth" \
    -H "Content-Type: application/json" \
    -d "{\"server_id\": \"$SERVER_ID\", \"server_name\": \"$SERVER_NAME\"}" 2>/dev/null || echo "{}")

SESSION_TOKEN=$(echo "$SERVER_RESPONSE" | python3 -c "import sys, json; print(json.load(sys.stdin).get('sessionToken', ''))" 2>/dev/null || echo "")
IDENTITY_TOKEN=$(echo "$SERVER_RESPONSE" | python3 -c "import sys, json; print(json.load(sys.stdin).get('identityToken', ''))" 2>/dev/null || echo "")

if [ -z "$SESSION_TOKEN" ] || [ -z "$IDENTITY_TOKEN" ]; then
    echo "WARNING: Could not fetch server tokens"
    echo "Response: $SERVER_RESPONSE"
    HAS_SERVER_TOKENS=false
else
    echo "Server tokens received"
    HAS_SERVER_TOKENS=true
fi
echo ""

# Mock client path
MOCK_CLIENT="$SCRIPT_DIR/../.github/scripts/mock-client.py"

# Check/install Python dependencies once
if [ "$SKIP_CLIENT" = false ] && [ -f "$MOCK_CLIENT" ]; then
    echo "=== Checking Python Dependencies ==="
    if ! python3 -c "import aioquic" 2>/dev/null; then
        echo "Installing aioquic for QUIC protocol support..."
        pip3 install aioquic --quiet || echo "WARNING: Could not install aioquic"
    else
        echo "aioquic is installed"
    fi
    if ! python3 -c "import cryptography" 2>/dev/null; then
        echo "Installing cryptography for client certificate generation..."
        pip3 install cryptography --quiet || echo "WARNING: Could not install cryptography"
    else
        echo "cryptography is installed"
    fi
    echo ""
fi

# Function to check server log for expected pattern (PASS if found)
check_log_pattern() {
    local pattern="$1"
    local start_line="$2"
    local log_file="$3"
    local new_lines match

    start_line="${start_line// /}"
    new_lines=$(tail -n +"$start_line" "$log_file" 2>/dev/null)

    if echo "$new_lines" | grep -qiE "$pattern"; then
        match=$(echo "$new_lines" | grep -iE "$pattern" | head -1 | sed 's/.*\[Hytale\]/[Hytale]/' | cut -c1-100)
        echo "  PASSED - Found: $match"
        return 0
    fi

    echo "  FAILED - Pattern not found: '$pattern'"
    echo "  Log excerpt:"
    echo "$new_lines" | grep -iE "connect|auth|reject|invalid|disconnect|SEVERE" | head -5 | sed 's/^/    /'
    return 1
}

# Function to check server log for error patterns (PASS if NOT found)
check_no_error_pattern() {
    local error_pattern="$1"
    local start_line="$2"
    local log_file="$3"
    local new_lines match

    start_line="${start_line// /}"
    new_lines=$(tail -n +"$start_line" "$log_file" 2>/dev/null)

    if echo "$new_lines" | grep -qiE "$error_pattern"; then
        match=$(echo "$new_lines" | grep -iE "$error_pattern" | head -1 | sed 's/.*\[/[/' | cut -c1-100)
        echo "  FAILED - Error found: $match"
        return 1
    fi

    echo "  PASSED - No errors found"
    return 0
}

# Run a single server test scenario
# Arguments: mode (with-tokens or no-tokens), port
run_server_test() {
    local mode="$1"
    local port="$2"
    local log_file="server_${mode}.log"
    local server_pid tail_pid timeout elapsed success bytecode_error
    local tests_passed tests_failed test_uuid test_username player_response player_token token_uuid
    local log_lines_before invalid_uuid anon_uuid

    echo ""
    echo "##############################################"
    echo "  Testing: $mode (port $port)"
    echo "##############################################"
    echo ""

    # Create separate data directory for this test mode (isolation)
    local data_dir="universe_${mode}"
    rm -rf "$data_dir" 2>/dev/null
    mkdir -p "$data_dir"
    echo "Using isolated data directory: $data_dir"

    # Start server based on mode
    if [ "$mode" = "with-tokens" ]; then
        echo "Starting server WITH explicit tokens..."
        "$JAVA_CMD" -Xmx2G -jar HytaleServerPatched.jar \
            --assets Assets.zip \
            --bind "0.0.0.0:$port" \
            --universe "$data_dir" \
            --disable-sentry \
            --session-token "$SESSION_TOKEN" \
            --identity-token "$IDENTITY_TOKEN" \
            > "$log_file" 2>&1 &
    else
        echo "Starting server WITHOUT tokens (auto-fetch mode)..."
        "$JAVA_CMD" -Xmx2G -jar HytaleServerPatched.jar \
            --assets Assets.zip \
            --bind "0.0.0.0:$port" \
            --universe "$data_dir" \
            --disable-sentry \
            > "$log_file" 2>&1 &
    fi

    server_pid=$!
    echo "Server PID: $server_pid"

    tail -f "$log_file" 2>/dev/null &
    tail_pid=$!

    timeout=60
    elapsed=0
    success=false
    bytecode_error=false

    echo "Monitoring server boot (max ${timeout}s)..."

    while kill -0 $server_pid 2>/dev/null; do
        sleep 2
        elapsed=$((elapsed + 2))

        if grep -qE "NoSuchFieldError|NoSuchMethodError|ClassNotFoundException|VerifyError" "$log_file" 2>/dev/null; then
            echo ""
            echo "FATAL: Bytecode patching error detected!"
            grep -E "NoSuchFieldError|NoSuchMethodError|ClassNotFoundException|VerifyError" "$log_file" | head -5
            bytecode_error=true
            break
        fi

        if grep -qi "Server Booted" "$log_file" 2>/dev/null; then
            echo ""
            echo "Server booted successfully!"
            success=true
            break
        fi

        if [ $((elapsed % 10)) -eq 0 ]; then
            echo "... waiting ($elapsed/${timeout}s)"
        fi

        if [ $elapsed -ge $timeout ]; then
            echo "Timeout reached"
            if grep -qi "Server Booted" "$log_file" 2>/dev/null; then
                success=true
            elif grep -q "Plugin manager started\|Universe ready" "$log_file" 2>/dev/null; then
                success=true
            fi
            break
        fi
    done

    kill "$tail_pid" 2>/dev/null || true

    echo ""
    echo "=== Boot Result ($mode) ==="
    if [ "$bytecode_error" = true ]; then
        echo "Server Boot: FAILED (bytecode error)"
        kill "$server_pid" 2>/dev/null || true
        return 1
    elif [ "$success" = true ]; then
        echo "Server Boot: OK"
    else
        echo "Server Boot: FAILED"
        kill "$server_pid" 2>/dev/null || true
        return 1
    fi

    # Show DualAuth messages
    echo ""
    echo "=== DualAuth Log Messages ==="
    grep -i "DualAuth" "$log_file" | head -10 || echo "No DualAuth messages found"
    echo ""

    tests_passed=0
    tests_failed=0

    if [ "$SKIP_CLIENT" = false ] && [ -f "$MOCK_CLIENT" ]; then
        if ! kill -0 "$server_pid" 2>/dev/null; then
            echo "ERROR: Server died after boot!"
            tail -30 "$log_file"
            return 1
        fi

        echo "=== Mock Client Connection Tests ($mode) ==="
        echo ""

        echo "Fetching F2P player token..."
        test_uuid=$(python3 -c "import uuid; print(uuid.uuid4())" 2>/dev/null || echo "test-$(date +%s)")
        test_username="LocalTestPlayer"

        player_response=$(curl -sf -X POST "$HYTALE_AUTH_URL/game-session/new" \
            -H "Content-Type: application/json" \
            -d "{\"uuid\": \"$test_uuid\", \"username\": \"$test_username\"}" 2>/dev/null || echo "{}")

        player_token=$(echo "$player_response" | python3 -c "import sys, json; d=json.load(sys.stdin); print(d.get('identityToken') or d.get('token', ''))" 2>/dev/null || echo "")

        if [ -z "$player_token" ]; then
            echo "Could not get player token"
        else
            echo "Player token received for $test_username"
        fi

        token_uuid=$(echo "$player_token" | cut -d. -f2 | python3 -c "
import sys, base64, json
payload = sys.stdin.read().strip()
payload += '=' * (4 - len(payload) % 4)
try:
    data = json.loads(base64.urlsafe_b64decode(payload))
    print(data.get('sub', ''))
except:
    print('')
" 2>/dev/null || echo "")

        if [ -n "$token_uuid" ]; then
            echo "Extracted UUID from token: $token_uuid"
            test_uuid="$token_uuid"
        fi
        echo ""

        # Test 1: Valid token connection
        echo "--- Test 1: Valid Token Connection ---"
        echo "  UUID: $test_uuid"
        echo "  Username: $test_username"
        echo ""

        log_lines_before=$(wc -l < "$log_file" 2>/dev/null | tr -d ' ')

        python3 "$MOCK_CLIENT" \
            --host 127.0.0.1 \
            --port "$port" \
            --uuid "$test_uuid" \
            --username "$test_username" \
            --token "$player_token" \
            --timeout 3 || true

        sleep 1

        # Test 1 passes if: auth grant obtained successfully AND no server-side errors
        # Error patterns that indicate failure:
        local error_patterns="Server session token not available|authentication unavailable|UUID mismatch|Invalid identity token"
        if check_no_error_pattern "$error_patterns" "$log_lines_before" "$log_file"; then
            # Also verify auth flow actually completed
            if echo "$(tail -n +"${log_lines_before// /}" "$log_file")" | grep -qiE "Successfully obtained authorization grant|Sending AuthGrant"; then
                echo "  (Auth grant obtained successfully)"
                tests_passed=$((tests_passed + 1))
            else
                echo "  FAILED - Auth grant not completed"
                tests_failed=$((tests_failed + 1))
            fi
        else
            tests_failed=$((tests_failed + 1))
        fi

        echo ""
        echo "--- Test 2: Invalid Token Connection ---"
        invalid_uuid=$(python3 -c "import uuid; print(uuid.uuid4())" 2>/dev/null || echo "invalid-uuid")
        echo "  UUID: $invalid_uuid"
        echo "  Username: FakePlayer"
        echo ""

        log_lines_before=$(wc -l < "$log_file" 2>/dev/null | tr -d ' ')

        python3 "$MOCK_CLIENT" \
            --host 127.0.0.1 \
            --port "$port" \
            --uuid "$invalid_uuid" \
            --username "FakePlayer" \
            --token "invalid.fake.token" \
            --timeout 3 || true

        sleep 1

        if check_log_pattern "invalid signature|token.*invalid|validation failed" "$log_lines_before" "$log_file"; then
            tests_passed=$((tests_passed + 1))
        else
            tests_failed=$((tests_failed + 1))
        fi

        echo ""
        echo "--- Test 3: No Token (Anonymous) ---"
        anon_uuid=$(python3 -c "import uuid; print(uuid.uuid4())" 2>/dev/null || echo "anon-uuid")
        echo "  UUID: $anon_uuid"
        echo "  Username: AnonPlayer"
        echo ""

        log_lines_before=$(wc -l < "$log_file" 2>/dev/null | tr -d ' ')

        python3 "$MOCK_CLIENT" \
            --host 127.0.0.1 \
            --port "$port" \
            --uuid "$anon_uuid" \
            --username "AnonPlayer" \
            --timeout 3 || true

        sleep 1

        if check_log_pattern "requires authentication|Rejecting development" "$log_lines_before" "$log_file"; then
            tests_passed=$((tests_passed + 1))
        else
            tests_failed=$((tests_failed + 1))
        fi

        echo ""
        echo "=============================================="
        echo "  Client Test Results ($mode)"
        echo "=============================================="
        echo "  Passed: $tests_passed / 3"
        echo "  Failed: $tests_failed / 3"
        if [ "$tests_failed" -eq 0 ]; then
            echo "  Status: ALL TESTS PASSED"
        else
            echo "  Status: SOME TESTS FAILED"
        fi
        echo ""
    fi

    echo "Stopping server..."
    kill "$server_pid" 2>/dev/null || true
    sleep 1
    if kill -0 "$server_pid" 2>/dev/null; then
        kill -9 "$server_pid" 2>/dev/null || true
    fi

    if [ "$SKIP_CLIENT" = false ] && [ "$tests_failed" -gt 0 ]; then
        return 1
    fi
    return 0
}

# Step 7: Run Server Tests
echo "=== Step 7: Server Tests ==="
echo "Token mode: $TOKEN_MODE"
echo ""

# Track overall results
RESULTS_WITH_TOKENS=""
RESULTS_NO_TOKENS=""

# Determine which modes to test
if [ "$TOKEN_MODE" = "both" ] || [ "$TOKEN_MODE" = "with-tokens" ]; then
    if [ "$HAS_SERVER_TOKENS" = true ]; then
        if run_server_test "with-tokens" 5520; then
            RESULTS_WITH_TOKENS="PASSED"
        else
            RESULTS_WITH_TOKENS="FAILED"
        fi
    else
        echo "Skipping with-tokens test - no server tokens available"
        RESULTS_WITH_TOKENS="SKIPPED"
    fi
fi

if [ "$TOKEN_MODE" = "both" ] || [ "$TOKEN_MODE" = "no-tokens" ]; then
    if run_server_test "no-tokens" 5521; then
        RESULTS_NO_TOKENS="PASSED"
    else
        RESULTS_NO_TOKENS="FAILED"
    fi
fi

# Final Summary
echo ""
echo "=============================================="
echo "  Final Summary"
echo "=============================================="
echo ""
echo "Patcher:"
echo "  - Compilation: OK"
echo "  - JAR patching: OK ($DUAL_CLASSES classes)"
echo ""

EXIT_CODE=0

if [ -n "$RESULTS_WITH_TOKENS" ]; then
    echo "Server Mode: WITH TOKENS"
    case "$RESULTS_WITH_TOKENS" in
        PASSED)
            echo "  - Boot: OK"
            echo "  - Client tests: PASSED"
            ;;
        SKIPPED)
            echo "  - SKIPPED (no tokens available)"
            ;;
        *)
            echo "  - FAILED"
            EXIT_CODE=1
            ;;
    esac
    echo ""
fi

if [ -n "$RESULTS_NO_TOKENS" ]; then
    echo "Server Mode: NO TOKENS (auto-fetch)"
    if [ "$RESULTS_NO_TOKENS" = "PASSED" ]; then
        echo "  - Boot: OK"
        echo "  - Client tests: PASSED"
    else
        echo "  - FAILED"
        EXIT_CODE=1
    fi
    echo ""
fi

if [ "$EXIT_CODE" -eq 0 ]; then
    echo "Overall Result: ALL TESTS PASSED"
else
    echo "Overall Result: SOME TESTS FAILED"
fi

echo ""
echo "Files:"
[ -f "server_with-tokens.log" ] && echo "  - server_with-tokens.log"
[ -f "server_no-tokens.log" ] && echo "  - server_no-tokens.log"
echo "  - HytaleServerPatched.jar"
echo ""
echo "Data Directories (isolated per test):"
[ -d "universe_with-tokens" ] && echo "  - universe_with-tokens/"
[ -d "universe_no-tokens" ] && echo "  - universe_no-tokens/"
echo "=============================================="

exit $EXIT_CODE
