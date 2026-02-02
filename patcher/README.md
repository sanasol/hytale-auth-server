# DualAuthPatcher - Hytale Server Dual Authentication

> **Authoritative Source** - This is the canonical location. Other projects (hytale-server-docker, Hytale-F2P) download from here.

Bytecode patcher that enables Hytale servers to accept authentication from **both** official `hytale.com` AND custom F2P auth servers simultaneously.

## Features

- **True Dual Auth**: Official Hytale clients AND F2P clients can connect to the same server
- **Omni-Auth (Decentralized)**: Automatically trusts and validates players using ANY auth authority by extracting embedded JWKs from their tokens.
- **Two-Level JWKS Cache**: 
    - **Transient Cache**: Instant trust for decentralized/per-request keys.
    - **Network Cache**: Multi-backend (Hytale + F2P) keys cached for 1 hour to prevent redundant fetch latency.
- **Mutual Authentication Emulation**: Satisfies strict client-side checks by echoing decentralized auth grants as server identity tokens.
- **Variable Domain Length**: Supports F2P domains from 4-16 characters.
- **Backward Compatible**: Accepts tokens from multiple F2P subdomains (sessions.*, auth.*, etc.)

## How It Works

```
Player connects with JWT token (may contain embedded "jwk")
         │
         ▼
┌─────────────────────────────────────────────────────────────┐
│                    PATCHED SERVER                            │
│                                                              │
│  1. EMBEDDED JWK CHECK (Omni-Auth)                           │
│     - Found "jwk" in header? Store in Transient Cache.       │
│                                                              │
│  2. JWKS FETCHING (Two-Level Cache)                          │
│     - Use Transient Key IF active.                           │
│     - MERGE with Network Cache (Hytale + F2P, 1h Refresh).   │
│                                                              │
│  3. TOKEN VALIDATION                                         │
│     - Signature verified against merged/transient key set.    │
│     - Issuer check: Accepts official, F2P, OR ANY issuer     │
│       if a Transient Key is present.                         │
│                                                              │
│  4. AUTH GRANT RESPONSE                                      │
│     - Decentralized Flow: SHORT-CIRCUIT network request.     │
│       Return echoed user token as Server Identity.           │
│       (Bypasses session service, satisfies mutual-auth).     │
│     - F2P Flow: Fetch F2P Server Identity.                   │
│     - Official Flow: Return official Server Identity.        │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

## Injected Classes

The patcher injects 6 helper classes into the server JAR:

| Class | Purpose |
|-------|---------|
| `DualJwksFetcher` | Fetches JWKS from backends, manages two-level cache (Network + Transient) |
| `DualAuthContext` | Volatile static storage for current request's issuer and transient keys |
| `DualAuthHelper` | Issuer validation, URL routing, and Mutual-Auth spoofing utilities |
| `DualTokenUtils` | Extracts balanced JSON structures (embedded JWKs) from token headers |
| `DualServerIdentity` | Server identity token management |
| `DualServerTokenManager` | Dual token set management (official + F2P) |

## Patched Methods

| Class | Method | Change |
|-------|--------|--------|
| `JWTValidator` | `fetchJwksFromService()` | Returns merged/transient JWKS from two-level cache |
| `JWTValidator` | `validateToken()` | Extracts embedded keys & selectively invalidates cache |
| `DualAuthHelper`| `isValidIssuer()` | Accepts ANY issuer when a transient key is active |
| `SessionServiceClient` | `requestAuthorizationGrantAsync()` | Routes to correct backend based on token |
| `AuthGrant` | `serialize()` | Echoes auth grant for decentralized clients; nullifies/replaces for others |

## Usage

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `HYTALE_AUTH_DOMAIN` | `auth.sanasol.ws` | F2P auth domain (4-16 chars) |

### Command Line

```bash
# Compile (requires ASM libraries in lib/)
javac -cp "lib/*" DualAuthPatcher.java

# Run
java -cp ".:lib/*" DualAuthPatcher <input.jar> <output.jar>

# Or with custom domain
HYTALE_AUTH_DOMAIN=my.server.io java -cp ".:lib/*" DualAuthPatcher input.jar output.jar
```

### Docker (Automatic)

When using `hytale-server-docker`, patching happens automatically on startup:

```yaml
environment:
  HYTALE_DUAL_AUTH: "true"  # Default
  HYTALE_AUTH_DOMAIN: "auth.sanasol.ws"
```

## Requirements

- **Java 21+** (for compilation and execution)
- **ASM 9.7** libraries:
  - `asm-9.7.jar`
  - `asm-tree-9.7.jar`
  - `asm-util-9.7.jar`
  - `asm-commons-9.7.jar`

### Download ASM Libraries

```bash
mkdir -p lib
cd lib
curl -LO https://repo1.maven.org/maven2/org/ow2/asm/asm/9.7/asm-9.7.jar
curl -LO https://repo1.maven.org/maven2/org/ow2/asm/asm-tree/9.7/asm-tree-9.7.jar
curl -LO https://repo1.maven.org/maven2/org/ow2/asm/asm-util/9.7/asm-util-9.7.jar
curl -LO https://repo1.maven.org/maven2/org/ow2/asm/asm-commons/9.7/asm-commons-9.7.jar
```

## Domain Configuration

### Unified Endpoint (Recommended)

F2P traffic routes to a single endpoint - no subdomains needed:

```
HYTALE_AUTH_DOMAIN=auth.sanasol.ws
                   └─ All requests go to https://auth.sanasol.ws
```

### Backward Compatibility

The patcher extracts the base domain for issuer validation:
- `auth.sanasol.ws` → base domain `sanasol.ws`
- Accepts issuers: `https://auth.sanasol.ws`, `https://sessions.sanasol.ws`, etc.

## Integration

### Download URL

```
https://raw.githubusercontent.com/sanasol/hytale-auth-server/master/patcher/DualAuthPatcher.java
```

### For hytale-server-docker

Downloads patcher automatically on startup. Set `HYTALE_DUAL_AUTH=true` (default).

### For Hytale-F2P Launcher

Downloads patcher at runtime when patching local servers.

### For Custom Integration

1. Download `DualAuthPatcher.java` from URL above
2. Download ASM libraries to `lib/`
3. Compile and run against your server JAR
4. Use the patched JAR with your F2P auth server

## Troubleshooting

### "No Ed25519 key found for kid"

JWKS merge failed. Check:
- Network connectivity to both backends
- F2P auth server is running and serving `/.well-known/jwks.json`

### "Invalid issuer"

Token issuer not accepted. Check:
- Issuer is full URL (e.g., `https://auth.sanasol.ws`, not just `auth.sanasol.ws`)
- Domain matches `HYTALE_AUTH_DOMAIN` or its base domain

### "0 patches applied"

JAR structure doesn't match expected classes. Check:
- JAR contains `com/hypixel/hytale/server/core/auth/JWTValidator.class`
- JAR is not already patched (delete `.patched_dual_auth` flag)

### Official players can't connect

- Verify `sessions.hytale.com` is reachable
- Check logs for "[DualAuth] Official JWKS: OK"

### F2P players can't connect

- Verify F2P auth server is running
- Check logs for "[DualAuth] F2P JWKS: OK"
- Ensure F2P launcher uses matching domain

## Version History

- **v10.0**: Omni-Auth / Decentralized support with Two-Level Cache and Mutual-Auth emulation.
- **v9.1**: Multi-threading fix (volatile context) for Netty compatibility.
- **v8.0**: Issuer-based routing for token refresh, profile lookup support
- **v7.0**: DualJwksFetcher for merged JWKS
- **v6.0**: True dual auth with context-based routing

## Related Projects

- [hytale-auth-server](https://github.com/sanasol/hytale-auth-server) - F2P authentication server
- [hytale-server-docker](https://github.com/sanasol/hytale-server-docker) - Docker server image
- [Hytale-F2P](https://github.com/amiayweb/Hytale-F2P) - Game launcher

## License

MIT License
