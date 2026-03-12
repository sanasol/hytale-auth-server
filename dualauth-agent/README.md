# DualAuth ByteBuddy Agent

A high-performance, non-intrusive **Java Agent** designed for Hytale dedicated servers. It enables seamless dual-authentication (Official + F2P) and decentralized identity (Omni-Auth) without modifying a single byte of the original server JAR.

---

## 🚀 The Evolution: Why We Migrated from ASM to ByteBuddy

Previously, authentication was handled via a **Static ASM Patcher**. While effective, it had several drawbacks:
*   **Destructive**: It required modifying the `HytaleServer.jar` on disk, creating maintenance headaches during server updates.
*   **Static Limitations**: Patching bytecode before runtime made it difficult to handle complex async boundaries and modern JVM optimizations.
*   **Fragile State**: Context propagation (keeping track of which issuer a player used) was prone to "leakage" across threads.

**The ByteBuddy Agent** solves this by shifting logic to the runtime:
*   **Pristine JARs**: Your original `HytaleServer.jar` remains untouched.
*   **Runtime Transformation**: Classes are transformed in memory as they load, allowing for much more surgical and reliable hooks.
*   **Async-Aware**: Specifically designed to track authentication context across the Hytale server's complex asynchronous login pipeline.

---

## ✨ Core Features

*   ✅ **Zero-Footprint Patching**: Simply add a flag to your startup command.
*   ✅ **Dual-Auth Protocol**: Simultaneously trust official Hytale issuers and your own custom F2P authentication nodes.
*   ✅ **Omni-Auth Support**: Support for decentralized, self-signed tokens with embedded JWKs (RFC 7515) for offline or private community nodes.
*   ✅ **Automatic JWKS Merging**: Dynamically aggregates signing keys from all trusted sources into a single, unified validator.
*   ✅ **Precise Type Resolution**: Automatically detects the correct internal Hytale claim wrapper (Identity vs. Session vs. Generic) to prevent casting errors.
*   ✅ **Auto-Fetch Identity**: If no server tokens are provided, the agent can automatically fetch a valid server identity from your F2P domain.
*   ✅ **Player Identity Registry**: Thread-safe `ConcurrentHashMap` tracks all online players with auth type (Official/F2P/Omni), accessible from any mod or plugin.
*   ✅ **In-Game Commands**: `/authinfo` and `/authlist` for real-time player auth inspection.
*   ✅ **Identity Protection**: Omni-Auth tokens are checked against the auth server's identity protection API to block impersonation of password-protected players.

---

## 🛠️ Installation & Usage

# Build agent
```bash
cd dualauth-agent
./gradlew build
```

The DualAuth Agent supports **two deployment modes**:

### 📋 **Mode 1: Java Agent (Recommended)**
*Loads before server startup, transforms classes as they load*

# Run server with Java agent
```bash
java -javaagent:dualauth-agent.jar -jar HytaleServer.jar --auth-mode authenticated
```

### 🔌 **Mode 2: Plugin (Dynamic Load)**
*Loads after server startup via Hytale's plugin system*

# Install as plugin
```bash
# Copy to plugins directory
cp dualauth-agent.jar /path/to/hytale-server/mods/
```

# Run server normally (no -javaagent flag needed)
```bash
java -jar HytaleServer.jar --auth-mode authenticated
```

## ⚙️ Configuration

The agent is configured via environment variables, allowing for easy deployment in Docker or CI/CD environments:

### **Core Authentication Settings**

| Variable | Description | Default | Use Cases |
| :--- | :--- | :--- | :--- |
| `HYTALE_AUTH_DOMAIN` | Your custom F2P authentication domain (alternative: `HYTALE_AUTH_SERVER`) | `auth.sanasol.ws` | **Community servers**: Set to your auth domain<br>**Development**: Use `localhost` for local testing<br>**Private networks**: Point to internal auth server |
| `HYTALE_TRUST_ALL_ISSUERS` | If `true`, enables Omni-Auth (accepts self-signed tokens) | `true` | **Public servers**: Set to `false` for security<br>**Private servers**: Set to `true` for flexibility<br>**Development**: Keep `true` for easy testing |
| `HYTALE_TRUST_OFFICIAL` | If `true`, trusts official Hytale issuers (sessions.hytale.com) | `true` | **Mixed servers**: Keep `true` to allow both<br>**F2P-only**: Set to `false` to block officials<br>**Testing**: Disable to force F2P authentication |
| `HYTALE_TRUSTED_ISSUERS` | Comma-separated list of trusted issuers (treated as public) | (Empty) | **Federated auth**: Add partner domains<br>**Multi-realm**: Trust multiple auth providers<br>**Migration**: Add legacy auth domains<br>**Performance**: Skip JWKS detection for trusted domains |

### **JWKS & Cache Configuration**

| Variable | Description | Default | Use Cases |
| :--- | :--- | :--- | :--- |
| `HYTALE_KEYS_CACHE_TTL` | JWKS cache time-to-live in seconds | `10800` (3 hours) | **High-security**: Set to `300` (5 min) for quick key rotation<br>**Stable environments**: Set to `21600` (6 hours) for performance<br>**Development**: Set to `60` for frequent key changes |
| `HYTALE_ISSUER_DETECTION_TTL` | Issuer detection cache TTL in seconds | `3600` (1 hour) | **Dynamic environments**: Set to `60` for quick discovery<br> **Stable setups**: Set to `21600` (3 hours) for efficiency<br>**Testing**: Set to `10` for rapid re-detection |

**📋 Cache Behavior Notes:**
- **JWKS Cache:** Stores public keys per issuer
- **Token Cache:** Stores identity tokens (federated per issuer, Omni-Auth per player-uuid)  
- **Cleanup:** Automatic when entries expire (based on TTL)
- **Memory Usage:** ~1KB per cached entry
- **Fixed Timeouts:** 5 seconds for JWKS detection (not configurable)

### **Issuer Detection & Security**

| Variable | Description | Default | Use Cases |
| :--- | :--- | :--- | :--- |
| `HYTALE_ISSUER_BLACKLIST` | Comma-separated list of blacklisted issuers | (Empty) | **Security**: Block known malicious domains<br>**Compliance**: Block competitor domains<br>**Moderation**: Block problematic issuers |
| `HYTALE_FORCE_ISSUER_DETECTION` | Force detection for all issuers (including officials) | `false` | **Debugging**: Set to `true` to detect all issuers<br>**Migration**: Force detection during transition<br>**Testing**: Verify detection logic works |

### **Server Identity**

| Variable | Description | Default | Use Cases |
| :--- | :--- | :--- | :--- |
| `HYTALE_SERVER_AUDIENCE` | Server audience UUID (alternative: `HYTALE_SERVER_ID`) | Auto-generated | **Production**: Set to your server's UUID<br>**Clustering**: Use same UUID across cluster<br>**Migration**: Preserve UUID during server moves |
| `HYTALE_SERVER_NAME` | Custom server name for identification | (Empty) | **Logging**: Identify server in logs<br>**Monitoring**: Distinguish servers in metrics<br>**Multi-server**: Name different instances |

### **Debug & Development**

| Variable | Type | Description | Default | Use Cases |
| :--- | :--- | :--- | :--- | :--- |
| `dualauth.debug` | System Property | Enable verbose debug logging | `false` | **Troubleshooting**: `-Ddualauth.debug=true`<br>**Development**: Enable during development<br>**Production**: Disable for performance |
| `dualauth.debug.connections` | System Property | Enable connection boundary logging only | `false` | **Connection tracking**: `-Ddualauth.debug.connections=true`<br>**Context debugging**: Monitor thread-local cleanup<br>**Multi-user**: Verify context isolation |

### **Example Configuration**

#### **🏠 Basic Community Server Setup**

**Java Agent Mode (Recommended):**
```bash
# Standard community server configuration
export HYTALE_AUTH_DOMAIN="auth.mycommunity.com"
export HYTALE_TRUST_ALL_ISSUERS="true"
export HYTALE_SERVER_NAME="MyCommunity Server"

java -javaagent:dualauth-agent.jar -jar HytaleServer.jar --auth-mode authenticated
```

**Or without flags, as a mod:**

```bash
# Run server normally:
java -jar HytaleServer.jar --auth-mode authenticated
```

#### **🔒 High-Security Production Server**
```bash
# Lock down production server for maximum security
export HYTALE_AUTH_DOMAIN="auth.production.com"
export HYTALE_TRUST_ALL_ISSUERS="false"
export HYTALE_TRUST_OFFICIAL="true"
export HYTALE_TRUSTED_ISSUERS="https://partner1.com,https://partner2.com"
export HYTALE_ISSUER_BLACKLIST="https://banned.com"
export HYTALE_KEYS_CACHE_TTL="300"  # 5 minutes for quick key rotation
export HYTALE_SERVER_AUDIENCE="12345678-1234-1234-1234-123456789abc"
export HYTALE_SERVER_NAME="Production Main"

java -javaagent:dualauth-agent.jar -jar HytaleServer.jar --auth-mode authenticated
```

#### **🧪 Development Environment**
```bash
# Local development with debugging
export HYTALE_AUTH_DOMAIN="localhost"
export HYTALE_TRUST_ALL_ISSUERS="true"
export HYTALE_FORCE_ISSUER_DETECTION="true"
export HYTALE_KEYS_CACHE_TTL="60"  # 1 minute for frequent changes
export HYTALE_ISSUER_DETECTION_TTL="10"  # Quick re-detection
export HYTALE_SERVER_NAME="Dev Server"

java -Ddualauth.debug=true -Ddualauth.debug.connections=true \
     -javaagent:dualauth-agent.jar -jar HytaleServer.jar --auth-mode authenticated
```

**Or with no flags using the agent as mod:**

```bash
java -jar HytaleServer.jar --auth-mode authenticated
```


#### **🌐 Multi-Realm Federation**
```bash
# Server trusting multiple authentication providers
export HYTALE_AUTH_DOMAIN="auth.realm1.com"
export HYTALE_TRUST_ALL_ISSUERS="false"
export HYTALE_TRUST_OFFICIAL="true"
export HYTALE_TRUSTED_ISSUERS="https://auth.realm2.com,https://auth.realm3.com"
export HYTALE_ISSUER_BLACKLIST="https://banned.com"
export HYTALE_SERVER_AUDIENCE="realm1-server-uuid"
export HYTALE_SERVER_NAME="Multi-Realm Hub"

java -javaagent:dualauth-agent.jar -jar HytaleServer.jar --auth-mode authenticated
```

#### **🐛 Troubleshooting Configuration**
```bash
# Enable all debugging for issue diagnosis
export HYTALE_AUTH_DOMAIN="auth.debug.com"
export HYTALE_FORCE_ISSUER_DETECTION="true"
export HYTALE_KEYS_CACHE_TTL="3600"  # Longer cache for debugging
export HYTALE_SERVER_NAME="Debug Server"

java -Ddualauth.debug=true -Ddualauth.debug.connections=true \
     -javaagent:dualauth-agent.jar -jar HytaleServer.jar --auth-mode authenticated
```

#### **🐳 Docker Deployment**
```dockerfile
FROM eclipse-temurin:21-jdk

# Environment variables
ENV HYTALE_AUTH_DOMAIN="auth.docker.com"
ENV HYTALE_TRUST_ALL_ISSUERS="false"
ENV HYTALE_TRUST_OFFICIAL="true"
ENV HYTALE_KEYS_CACHE_TTL="3600"
ENV HYTALE_SERVER_NAME="Docker Server"

COPY dualauth-agent.jar /app/
COPY HytaleServer.jar /app/

WORKDIR /app
CMD ["java", "-javaagent:dualauth-agent.jar", "-jar", "HytaleServer.jar", "--auth-mode", "authenticated"]
```

#### **⚡ High-Performance Setup**
```bash
# Optimized for maximum performance
export HYTALE_AUTH_DOMAIN="auth.fast.com"
export HYTALE_TRUST_ALL_ISSUERS="false"
export HYTALE_KEYS_CACHE_TTL="7200"  # 2 hours
export HYTALE_ISSUER_DETECTION_TTL="1800"  # 30 minutes
export HYTALE_SERVER_NAME="High-Performance Server"

java -javaagent:dualauth-agent.jar -jar HytaleServer.jar --auth-mode authenticated
```

---

## 💬 In-Game Commands

The agent registers two in-game commands for real-time player auth inspection.

### `/authinfo [player]`

Show authentication details for yourself or a target player.

- **Self-info** (no argument): Any player can run `/authinfo` to see their own auth type.
- **Target player** (argument): Requires admin permission. Accepts player name or UUID.

**Output:**
```
[DualAuth] === Auth Info: Sanasol ===
  UUID:      adfd7538-edba-459d-a950-05a704e4f42a
  Auth Type: F2P
  Issuer:    https://auth.sanasol.ws
  Session:   2h 15m ago
  F2P:       true
  Omni-Auth: false
  Agent:     v1.1.20
```

### `/authlist`

List all online players with their auth type. Requires admin permission.

**Output:**
```
[DualAuth] Online players (3):
  Name             | Type          | UUID
  -----------------+---------------+--------------------------------------
  Sanasol          | F2P           | adfd7538-edba-459d-a950-05a704e4f42a
  OfficialPlayer   | OFFICIAL      | 12345678-1234-1234-1234-123456789abc
  SelfHosted       | OMNI (self-s) | 87654321-4321-4321-4321-cba987654321
```

### Command Permissions

Admin access is checked in order:

1. **UUID whitelist**: `DUALAUTH_ADMIN_UUIDS` environment variable (comma-separated UUIDs)
2. **Game permission**: `dualauth.admin` permission node
3. **Fallback permission**: Configurable via `DUALAUTH_ADMIN_PERMISSION` env var (default: `server.commands.who`)

| Variable | Description | Default |
| :--- | :--- | :--- |
| `DUALAUTH_ADMIN_UUIDS` | Comma-separated list of admin UUIDs | (Empty) |
| `DUALAUTH_ADMIN_PERMISSION` | Fallback game permission node | `server.commands.who` |

### permissions.json Setup

Add the following to your server's `permissions.json` to grant command access:

```json
{
  "groups": {
    "admin": {
      "permissions": [
        "dualauth.admin",
        "server.commands.who"
      ]
    }
  },
  "players": {
    "adfd7538-edba-459d-a950-05a704e4f42a": {
      "group": "admin"
    }
  }
}
```

Or use the UUID whitelist (no permissions.json changes needed):
```bash
export DUALAUTH_ADMIN_UUIDS="adfd7538-edba-459d-a950-05a704e4f42a,other-admin-uuid"
```

---

## 🔌 Mod Developer API — Player Identity Registry

The agent maintains a thread-safe registry of all online players with their authentication type. Mod developers can query this to implement auth-aware features (e.g., F2P-only areas, different permissions, auth badges).

### Accessing the Registry

The registry is available via static methods on `DualAuthContext`. Since the agent runs on the bootstrap classloader, access it via reflection from your mod:

```java
// Get the DualAuthContext class from the bootstrap classloader
Class<?> ctx = ClassLoader.getSystemClassLoader()
    .loadClass("ws.sanasol.dualauth.context.DualAuthContext");
```

### Available Methods

| Method | Returns | Description |
| :--- | :--- | :--- |
| `getPlayerInfo(String uuid)` | `PlayerAuthInfo` or `null` | Full auth info for a player |
| `isPlayerF2P(String uuid)` | `boolean` | `true` if player authenticated via F2P |
| `isPlayerOfficial(String uuid)` | `boolean` | `true` if player authenticated via official Hytale |
| `getOnlinePlayers()` | `Map<String, PlayerAuthInfo>` | Snapshot of all online players |

### PlayerAuthInfo Fields

| Field | Type | Description |
| :--- | :--- | :--- |
| `uuid` | `String` | Player UUID |
| `username` | `String` | Player username |
| `isF2P` | `boolean` | Authenticated via F2P auth server |
| `isOmni` | `boolean` | Authenticated via Omni-Auth (self-signed token) |
| `issuer` | `String` | Token issuer URL (e.g., `https://auth.sanasol.ws`) |
| `authenticatedAt` | `long` | System.currentTimeMillis() when auth completed |

### Example: Query Player Auth Type from a Mod

```java
import java.lang.reflect.Method;
import java.util.Map;

public class MyModAuthHelper {

    private static Class<?> ctxClass;
    private static Method getPlayerInfoMethod;
    private static boolean initialized = false;

    private static void init() {
        if (initialized) return;
        try {
            ctxClass = ClassLoader.getSystemClassLoader()
                .loadClass("ws.sanasol.dualauth.context.DualAuthContext");
            getPlayerInfoMethod = ctxClass.getMethod("getPlayerInfo", String.class);
            initialized = true;
        } catch (Exception e) {
            // DualAuth agent not loaded — all players are official
            initialized = true;
        }
    }

    /**
     * Check if a player is F2P.
     * Returns false if DualAuth agent is not loaded.
     */
    public static boolean isF2P(String uuid) {
        init();
        if (getPlayerInfoMethod == null) return false;
        try {
            Object info = getPlayerInfoMethod.invoke(null, uuid);
            if (info == null) return false;
            return info.getClass().getField("isF2P").getBoolean(info);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Get auth type label for display.
     */
    public static String getAuthType(String uuid) {
        init();
        if (getPlayerInfoMethod == null) return "OFFICIAL";
        try {
            Object info = getPlayerInfoMethod.invoke(null, uuid);
            if (info == null) return "UNKNOWN";
            boolean isOmni = info.getClass().getField("isOmni").getBoolean(info);
            boolean isF2P = info.getClass().getField("isF2P").getBoolean(info);
            if (isOmni) return "OMNI";
            if (isF2P) return "F2P";
            return "OFFICIAL";
        } catch (Exception e) {
            return "UNKNOWN";
        }
    }

    /**
     * Get the issuer URL for a player's token.
     */
    public static String getIssuer(String uuid) {
        init();
        if (getPlayerInfoMethod == null) return null;
        try {
            Object info = getPlayerInfoMethod.invoke(null, uuid);
            if (info == null) return null;
            return (String) info.getClass().getField("issuer").get(info);
        } catch (Exception e) {
            return null;
        }
    }
}
```

### Lifecycle

- **Registration**: Players are registered automatically after successful JWT validation
- **Unregistration**: Players are removed on disconnect (connection close)
- **Thread safety**: The registry uses `ConcurrentHashMap` — safe to query from any thread

