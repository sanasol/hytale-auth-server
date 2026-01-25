# DualAuthPatcher - Hytale Server Dual Authentication

> **Authoritative Source** - This is the canonical location. Other projects (hytale-server-docker, Hytale-F2P) download from here.

Bytecode patcher that enables Hytale servers to accept authentication from **both** official `hytale.com` AND custom F2P auth servers simultaneously.

## Features

- **True Dual Auth**: Official Hytale clients AND F2P clients can connect to the same server
- **JWKS Merging**: Fetches and merges public keys from both authentication backends
- **Issuer Routing**: Routes auth requests to the correct backend based on player's token issuer
- **Variable Domain Length**: Supports F2P domains from 4-16 characters
- **Backward Compatible**: Accepts tokens from multiple F2P subdomains (sessions.*, auth.*, etc.)

## How It Works

```
Player connects with JWT token
         │
         ▼
┌─────────────────────────────────────────────────────────────┐
│                    PATCHED SERVER                            │
│                                                              │
│  1. JWKS loaded from BOTH backends (merged)                 │
│     - https://sessions.hytale.com/.well-known/jwks.json     │
│     - https://{F2P_DOMAIN}/.well-known/jwks.json            │
│                                                              │
│  2. Token signature verified against merged key set          │
│                                                              │
│  3. Issuer validation accepts BOTH:                          │
│     - *.hytale.com (official)                               │
│     - *.{F2P_BASE_DOMAIN} (F2P, e.g., *.sanasol.ws)        │
│                                                              │
│  4. Auth requests routed based on token's issuer:            │
│     - hytale.com token → sessions.hytale.com                │
│     - F2P token → https://{F2P_DOMAIN}                      │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

## Injected Classes

The patcher injects 5 helper classes into the server JAR:

| Class | Purpose |
|-------|---------|
| `DualJwksFetcher` | Fetches JWKS from both backends, merges keys |
| `DualAuthContext` | Thread-local storage for current request's issuer |
| `DualAuthHelper` | Issuer validation, URL routing utilities |
| `DualServerIdentity` | Server identity token management |
| `DualServerTokenManager` | Dual token set management (official + F2P) |

## Patched Methods

| Class | Method | Change |
|-------|--------|--------|
| `JWTValidator` | `fetchJwksFromService()` | Returns merged JWKS from both backends |
| `JWTValidator` | `validateToken()` | Accepts both official and F2P issuers |
| `SessionServiceClient` | `requestAuthorizationGrantAsync()` | Routes to correct backend |
| `SessionServiceClient` | `refreshSessionAsync()` | Routes refresh to correct backend |
| `AuthGrant` | Constructor | Nullifies server identity for F2P clients |

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
https://raw.githubusercontent.com/sanasol/hytale-auth-server/main/patcher/DualAuthPatcher.java
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

- **v8.0**: Issuer-based routing for token refresh, profile lookup support
- **v7.0**: DualJwksFetcher for merged JWKS
- **v6.0**: True dual auth with context-based routing

## Related Projects

- [hytale-auth-server](https://github.com/sanasol/hytale-auth-server) - F2P authentication server
- [hytale-server-docker](https://github.com/sanasol/hytale-server-docker) - Docker server image
- [Hytale-F2P](https://github.com/amiayweb/Hytale-F2P) - Game launcher

## License

MIT License
