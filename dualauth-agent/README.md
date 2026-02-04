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

# Build the agent (requires Gradle installed locally)
```bash
cd dualauth-agent
./gradlew shadowJar
```

# Run the server
```bash
java -javaagent:dualauth-agent.jar -jar HytaleServer.jar -- bare --auth-mode authenticated
```

## ⚙️ Configuration

The agent is configured via environment variables, allowing for easy deployment in Docker or CI/CD environments:

| Variable | Description | Default |
| :--- | :--- | :--- |
| `HYTALE_AUTH_DOMAIN` | Your custom F2P authentication domain. | `auth.sanasol.ws` |
| `HYTALE_TRUST_ALL_ISSUERS` | If `true`, enables Omni-Auth (accepts self-signed tokens). | `true` |
| `HYTALE_TRUSTED_ISSUERS` | Comma-separated list of secondary trusted issuers. | (Empty) |
| `dualauth.debug` | Set to `true` (system property) for verbose transformation logs. | `false` |

