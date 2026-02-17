# DualAuth ByteBuddy Agent (v1.0.0)

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

