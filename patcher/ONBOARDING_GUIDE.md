# Onboarding Guide: Connecting to DualAuth Hytale Servers

> How to make your custom launcher, auth server, or modded client compatible with Hytale F2P servers running the DualAuth ByteBuddy Agent.

**Credits:** DualAuth Agent and Omni-Auth by [@soyelmismo](https://github.com/soyelmismo) ([RusTale](https://github.com/soyelmismo/RusTale)). F2P infrastructure by [@sanasol](https://github.com/sanasol).

---

## Table of Contents

- [How It Works](#how-it-works)
- [Choose Your Integration Path](#choose-your-integration-path)
- [Path A: Omni-Auth (Easiest - No Server Needed)](#path-a-omni-auth-easiest---no-server-needed)
- [Path B: Run Your Own Auth Server (Federated)](#path-b-run-your-own-auth-server-federated)
- [Path C: Use the Public F2P Auth Server](#path-c-use-the-public-f2p-auth-server)
- [Hosting a Game Server](#hosting-a-game-server)
- [Client Patching](#client-patching)
- [API Reference](#api-reference)
- [Troubleshooting](#troubleshooting)

---

## How It Works

Hytale game servers normally only accept tokens signed by `hytale.com`. The **DualAuth ByteBuddy Agent** is a Java agent that runs alongside the game server and extends authentication to accept tokens from multiple sources:

```
java -javaagent:dualauth-agent.jar -jar HytaleServer.jar
```

The agent intercepts the server's JWT validation at runtime and adds support for:

| Auth Mode | Description | Requires Server? |
|-----------|-------------|-------------------|
| **Official** | Tokens from `hytale.com` (bought the game) | No (built-in) |
| **Omni-Auth** | Self-signed tokens with embedded keys | No |
| **F2P (Federated)** | Tokens from any auth server with a JWKS endpoint | Yes |

**Token priority**: Official > Omni-Auth > F2P > Federated

The agent is non-destructive -- the original `HytaleServer.jar` is never modified.

---

## Choose Your Integration Path

| I want to... | Recommended Path | Difficulty |
|--------------|-----------------|------------|
| Build a launcher that connects to any F2P server | [Path A: Omni-Auth](#path-a-omni-auth-easiest---no-server-needed) | Easy |
| Run my own auth server for my community | [Path B: Federated](#path-b-run-your-own-auth-server-federated) | Medium |
| Build a launcher using the existing public F2P server | [Path C: Public F2P](#path-c-use-the-public-f2p-auth-server) | Easy |
| Host a game server that accepts F2P players | [Hosting a Game Server](#hosting-a-game-server) | Easy |
| Patch the game client to connect to F2P auth | [Client Patching](#client-patching) | Medium |

---

## Path A: Omni-Auth (Easiest - No Server Needed)

Omni-Auth lets your launcher generate self-signed tokens with embedded cryptographic keys. No auth server required -- the token carries its own verification key.

### How It Works

1. Your launcher generates an Ed25519 keypair
2. Builds a JWT with the public key embedded in the header (`jwk` field)
3. Signs it with the private key
4. Game server verifies the signature using the embedded public key

### Token Format

**Header:**
```json
{
  "alg": "EdDSA",
  "typ": "JWT",
  "jwk": {
    "kty": "OKP",
    "crv": "Ed25519",
    "x": "<base64url-public-key>",
    "d": "<base64url-private-key>",
    "use": "sig"
  }
}
```

**Payload:**
```json
{
  "iss": "https://my-launcher.example.com",
  "sub": "player-uuid-here",
  "username": "PlayerName",
  "aud": "hytale-server",
  "iat": 1706000000,
  "exp": 1706036000,
  "scope": "hytale:server hytale:client",
  "entitlements": ["game.base"]
}
```

### Required Claims

| Claim | Required | Description |
|-------|----------|-------------|
| `sub` | Yes | Player UUID (consistent across sessions) |
| `iss` | Yes | Issuer URL (your launcher's identity) |
| `iat` | Yes | Issued-at timestamp (unix seconds) |
| `exp` | Yes | Expiration timestamp (unix seconds) |
| `scope` | Yes | Must include `hytale:server hytale:client` |
| `username` | Recommended | Player display name (also accepts `name` or `nickname`) |
| `aud` | Recommended | `hytale-server` or the specific server UUID |
| `entitlements` | Recommended | `["game.base"]` |

### Code Examples

#### Node.js

```javascript
const crypto = require('crypto');

function generateOmniToken(playerUuid, username, issuer = 'https://my-launcher.example.com') {
  const { privateKey, publicKey } = crypto.generateKeyPairSync('ed25519');

  const privateJwk = privateKey.export({ format: 'jwk' });
  const publicJwk = publicKey.export({ format: 'jwk' });

  const header = {
    alg: 'EdDSA',
    typ: 'JWT',
    jwk: {
      kty: 'OKP', crv: 'Ed25519',
      x: publicJwk.x, d: privateJwk.d,
      use: 'sig', alg: 'EdDSA'
    }
  };

  const now = Math.floor(Date.now() / 1000);
  const payload = {
    iss: issuer,
    sub: playerUuid,
    username: username,
    aud: 'hytale-server',
    iat: now,
    exp: now + 36000, // 10 hours
    scope: 'hytale:server hytale:client',
    entitlements: ['game.base']
  };

  const b64url = (obj) => Buffer.from(JSON.stringify(obj)).toString('base64url');
  const signingInput = `${b64url(header)}.${b64url(payload)}`;
  const signature = crypto.sign(null, Buffer.from(signingInput), privateKey);

  return `${signingInput}.${signature.toString('base64url')}`;
}
```

#### Python

```python
import base64, json, time, uuid
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives import serialization

def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b'=').decode()

def generate_omni_token(player_uuid: str, username: str, issuer: str = 'https://my-launcher.example.com') -> str:
    key = Ed25519PrivateKey.generate()
    pub = key.public_key()

    pub_bytes = pub.public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)
    priv_bytes = key.private_bytes(serialization.Encoding.Raw, serialization.PrivateFormat.Raw, serialization.NoEncryption())

    header = {
        'alg': 'EdDSA', 'typ': 'JWT',
        'jwk': {'kty': 'OKP', 'crv': 'Ed25519', 'x': b64url(pub_bytes), 'd': b64url(priv_bytes), 'use': 'sig'}
    }

    now = int(time.time())
    payload = {
        'iss': issuer, 'sub': player_uuid, 'username': username,
        'aud': 'hytale-server', 'iat': now, 'exp': now + 36000,
        'scope': 'hytale:server hytale:client', 'entitlements': ['game.base']
    }

    h = b64url(json.dumps(header, separators=(',', ':')).encode())
    p = b64url(json.dumps(payload, separators=(',', ':')).encode())
    sig = key.sign(f'{h}.{p}'.encode())
    return f'{h}.{p}.{b64url(sig)}'
```

#### Rust

```rust
use ed25519_dalek::{SigningKey, Signer};
use base64::{Engine, engine::general_purpose::URL_SAFE_NO_PAD};

fn generate_omni_token(uuid: &str, username: &str, issuer: &str) -> String {
    let key = SigningKey::generate(&mut rand::thread_rng());
    let pub_key = key.verifying_key();

    let jwk = serde_json::json!({
        "kty": "OKP", "crv": "Ed25519",
        "x": URL_SAFE_NO_PAD.encode(pub_key.as_bytes()),
        "d": URL_SAFE_NO_PAD.encode(key.as_bytes()),
        "use": "sig", "alg": "EdDSA"
    });

    let header = serde_json::json!({"alg": "EdDSA", "typ": "JWT", "jwk": jwk});
    let now = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_secs();
    let payload = serde_json::json!({
        "iss": issuer, "sub": uuid, "username": username,
        "aud": "hytale-server", "iat": now, "exp": now + 36000,
        "scope": "hytale:server hytale:client", "entitlements": ["game.base"]
    });

    let h = URL_SAFE_NO_PAD.encode(header.to_string().as_bytes());
    let p = URL_SAFE_NO_PAD.encode(payload.to_string().as_bytes());
    let sig = key.sign(format!("{h}.{p}").as_bytes());
    format!("{h}.{p}.{}", URL_SAFE_NO_PAD.encode(sig.to_bytes()))
}
```

### Server Requirements for Omni-Auth

The game server must have `HYTALE_TRUST_ALL_ISSUERS=true` (this is the default). If the server operator has restricted issuers, your launcher's issuer URL must be in their `HYTALE_TRUSTED_ISSUERS` list.

### Important Notes

- **Keep the keypair consistent** per player session. Generate once, reuse for the session duration.
- The private key (`d`) in the header allows the server to sign response tokens back to the client using the same key.
- The server only uses the public key (`x`) for verification -- `d` is never extracted for other purposes.
- Player UUIDs should be consistent per player (generate once, persist locally).

---

## Path B: Run Your Own Auth Server (Federated)

Run your own authentication server for your community. Game servers with the DualAuth agent will automatically discover your JWKS endpoint and trust your tokens.

### Minimum Requirements

Your auth server must implement:

#### 1. JWKS Endpoint (Required)

```
GET /.well-known/jwks.json
```

Response:
```json
{
  "keys": [{
    "kty": "OKP",
    "crv": "Ed25519",
    "x": "<base64url-public-key>",
    "kid": "my-key-2026",
    "use": "sig",
    "alg": "EdDSA"
  }]
}
```

This is the **only hard requirement**. The DualAuth agent discovers this endpoint automatically from the `iss` claim in player tokens: `{issuer}/.well-known/jwks.json`.

#### 2. Session Endpoint (Recommended)

```
POST /game-session/new
```

Creates a new game session for a player. Returns signed tokens.

Request body:
```json
{
  "uuid": "player-uuid",
  "username": "PlayerName"
}
```

Response:
```json
{
  "identityToken": "<jwt>",
  "sessionToken": "<jwt>",
  "expiresIn": 36000,
  "tokenType": "Bearer"
}
```

#### 3. Server Auto-Auth Endpoint (Recommended)

```
POST /server/auto-auth
```

Called by the DualAuth agent's warmup thread to pre-fetch server tokens from your auth server.

Request body:
```json
{
  "serverUuid": "server-uuid",
  "serverName": "My Server"
}
```

Response:
```json
{
  "identityToken": "<jwt>",
  "sessionToken": "<jwt>"
}
```

#### 4. Auth Grant Flow (For Full Compatibility)

For the complete server-join handshake:

```
POST /game-session/authorize    → { "authorizationGrant": "<jwt>" }
POST /server-join/auth-token    → { "accessToken": "<jwt>", "tokenType": "Bearer" }
```

### JWT Token Requirements

| Field | Value |
|-------|-------|
| Algorithm | `EdDSA` (Ed25519) -- RSA and EC also supported |
| Issuer (`iss`) | Your server's public URL (e.g., `https://auth.example.com`) |
| Subject (`sub`) | Player UUID |
| Expiration (`exp`) | Unix timestamp (recommend 10 hours from `iat`) |
| Scope (`scope`) | `hytale:server hytale:client` |

**Critical**: The `iss` URL must be publicly accessible. The agent fetches `{iss}/.well-known/jwks.json` to discover your signing keys.

**Critical**: Persist your Ed25519 keys across restarts. If keys change, all existing tokens become invalid.

### Profile Endpoints (Optional)

Game servers look up player names via these endpoints. Implement them for proper name display:

```
GET /profile/uuid/{uuid}       → { "uuid": "...", "username": "..." }
GET /profile/username/{name}   → { "uuid": "...", "username": "..." }
GET /my-account/game-profile   → { "uuid": "...", "username": "...", "entitlements": ["game.base"] }
```

### Reference Implementation

The sanasol F2P auth server is open source and implements all endpoints:
- **Source**: https://github.com/sanasol/hytale-auth-server
- **Stack**: Node.js + Express + Ed25519 + Redis

---

## Path C: Use the Public F2P Auth Server

The simplest path for launcher developers -- use the existing public auth server at `auth.sanasol.ws`.

### Endpoints

| Endpoint | URL |
|----------|-----|
| Base URL | `https://auth.sanasol.ws` |
| JWKS | `https://auth.sanasol.ws/.well-known/jwks.json` |
| New Session | `POST https://auth.sanasol.ws/game-session/new` |
| Refresh Session | `POST https://auth.sanasol.ws/game-session/refresh` |
| Game Profile | `GET https://auth.sanasol.ws/my-account/game-profile` |
| Auth Grant | `POST https://auth.sanasol.ws/game-session/authorize` |
| Token Exchange | `POST https://auth.sanasol.ws/server-join/auth-token` |
| Server Auto-Auth | `POST https://auth.sanasol.ws/server/auto-auth` |

### Quick Start: Get a Token

```bash
curl -X POST https://auth.sanasol.ws/game-session/new \
  -H "Content-Type: application/json" \
  -d '{"uuid": "your-uuid-here", "username": "YourName"}'
```

Response:
```json
{
  "identityToken": "eyJ...",
  "sessionToken": "eyJ...",
  "expiresIn": 36000,
  "tokenType": "Bearer"
}
```

### Launcher Integration Flow

```
1. Generate or load a persistent player UUID
2. Let the user choose a username
3. POST /game-session/new → get identityToken + sessionToken
4. Store tokens locally (valid for 10 hours)
5. Use identityToken when connecting to game servers
6. POST /game-session/refresh before expiry to renew
```

### Notes

- No registration or password required (F2P mode)
- Anyone can use any username (no uniqueness enforcement)
- Player UUID should be generated once and persisted locally
- The public server is community-maintained and free to use
- Cosmetics and avatar data are shared by username

---

## Hosting a Game Server

### Option 1: Docker (Recommended)

```bash
docker run -d \
  -p 5720:5720/udp \
  -v ./data:/data \
  -e HYTALE_TRUST_ALL_ISSUERS=true \
  -e HYTALE_TRUST_OFFICIAL=true \
  sanasol/hytale-server:latest
```

The Docker image includes the DualAuth agent and automatically:
- Downloads `HytaleServer.jar` and `Assets.zip` on first run
- Downloads `dualauth-agent.jar` from GitHub releases
- Starts the server with `-javaagent:dualauth-agent.jar`
- Auto-fetches F2P tokens from `auth.sanasol.ws`

### Option 2: Manual Setup

1. Download the game server files (`HytaleServer.jar`, `Assets.zip`)
2. Download the agent:
   ```bash
   curl -sfL https://github.com/sanasol/hytale-auth-server/releases/latest/download/dualauth-agent.jar -o dualauth-agent.jar
   ```
3. Start the server:
   ```bash
   java -javaagent:dualauth-agent.jar \
     -jar HytaleServer.jar \
     --assets Assets.zip \
     --bind 0.0.0.0:5720 \
     --auth-mode authenticated \
     --disable-sentry
   ```

### Option 3: Pterodactyl Panel

Import the egg from `egg-patched/egg-hytale-server.json`. The egg handles agent download and startup automatically.

### Server Configuration

| Environment Variable | Default | Description |
|---------------------|---------|-------------|
| `HYTALE_TRUST_ALL_ISSUERS` | `true` | Accept tokens from any issuer (Omni-Auth + federated) |
| `HYTALE_TRUST_OFFICIAL` | `true` | Accept official `hytale.com` tokens |
| `HYTALE_AUTH_DOMAIN` | `auth.sanasol.ws` | F2P auth server domain |
| `HYTALE_TRUSTED_ISSUERS` | (empty) | Comma-separated list of explicitly trusted issuer URLs |
| `HYTALE_ISSUER_BLACKLIST` | (empty) | Comma-separated list of blocked issuer URLs |
| `HYTALE_KEYS_CACHE_TTL` | `3600` | How long to cache JWKS keys (seconds) |
| `HYTALE_SERVER_NAME` | (auto) | Server display name for token requests |
| `DUALAUTH_LOGGING_ENABLED` | `false` | Enable agent debug logging |

### Trust Configuration Examples

**Open server (accept everyone):**
```bash
HYTALE_TRUST_ALL_ISSUERS=true
HYTALE_TRUST_OFFICIAL=true
```

**Restrict to specific auth servers:**
```bash
HYTALE_TRUST_ALL_ISSUERS=false
HYTALE_TRUST_OFFICIAL=true
HYTALE_TRUSTED_ISSUERS=https://auth.sanasol.ws,https://auth.mycommunity.com
```

**F2P only (no official players):**
```bash
HYTALE_TRUST_ALL_ISSUERS=true
HYTALE_TRUST_OFFICIAL=false
```

### Verify Agent Is Working

Check startup logs for:
```
DualAuth Agent: enabled
DualAuth Agent: trust_all_issuers=true, trust_official=true
TRANSFORMED: JWTValidator
TRANSFORMED: SessionServiceClient
TRANSFORMED: ServerAuthManager
TRANSFORMED: HandshakeHandler
```

---

## Client Patching

To connect to F2P servers, the game client needs its auth domain changed from `hytale.com` to your F2P domain. This is separate from the server-side agent.

### How Client Patching Works

The Hytale client (`HytaleClient.dll` on Windows) is a .NET binary with hardcoded auth URLs pointing to `hytale.com` subdomains. Patching replaces these domain strings with your F2P domain.

**Key constraints:**
- .NET strings are **UTF-16LE** encoded (2 bytes per character)
- Domain must be **4-16 characters** long
- F2P clients use a **single endpoint** (no subdomains)
- On macOS: must patch **before** code signing

### Patching Logic

For domains up to 10 characters, direct replacement is used. For domains 11-16 characters, a split mode packs the domain across the subdomain prefix and main domain slots.

Either way, the result is that all auth traffic routes to a single endpoint: `https://{your-domain}`.

### Reference Implementation

See the F2P launcher's patcher: [`Hytale-F2P/backend/utils/clientPatcher.js`](https://github.com/sanasol/Hytale-F2P)

The patcher:
1. Reads `HytaleClient.dll`
2. Finds UTF-16LE encoded `hytale.com` strings
3. Replaces with the configured F2P domain
4. Writes the patched binary

### Using the Public F2P Domain

If you use `auth.sanasol.ws` (14 characters), your patched client will route all auth traffic to the public F2P server. No need to run your own auth server.

---

## API Reference

### Authentication Flow Diagram

```
Client                          Auth Server                    Game Server
  │                                 │                              │
  │  POST /game-session/new         │                              │
  │  {uuid, username}               │                              │
  │ ──────────────────────────────► │                              │
  │                                 │                              │
  │  {identityToken, sessionToken}  │                              │
  │ ◄────────────────────────────── │                              │
  │                                 │                              │
  │  Connect with identityToken     │                              │
  │ ─────────────────────────────────────────────────────────────► │
  │                                 │                              │
  │                                 │  POST /game-session/authorize│
  │                                 │  {identityToken, aud}        │
  │                                 │ ◄──────────────────────────── │
  │                                 │                              │
  │                                 │  {authorizationGrant}        │
  │                                 │ ────────────────────────────► │
  │                                 │                              │
  │  AuthGrant packet               │                              │
  │ ◄─────────────────────────────────────────────────────────────│
  │                                 │                              │
  │  POST /server-join/auth-token   │                              │
  │  {authorizationGrant, cert}     │                              │
  │ ──────────────────────────────► │                              │
  │                                 │                              │
  │  {accessToken}                  │                              │
  │ ◄────────────────────────────── │                              │
  │                                 │                              │
  │  Final auth with accessToken    │                              │
  │ ─────────────────────────────────────────────────────────────► │
  │                                 │                              │
  │  Connected!                     │                              │
```

For **Omni-Auth**, the flow is simpler -- the client sends the self-signed token directly to the game server, which validates it using the embedded key. No auth server involved.

### Token Lifetimes

| Token Type | Default TTL | Purpose |
|-----------|-------------|---------|
| Identity Token | 10 hours | Main player identity |
| Session Token | 10 hours | Server-side session tracking |
| Auth Grant | 10 hours | Temporary server-join authorization |
| Access Token | 10 hours | Final token with certificate binding |

---

## Troubleshooting

### "Connection rejected" or "Invalid token"

1. Check the server has the DualAuth agent enabled (look for `DualAuth Agent: enabled` in logs)
2. If using Omni-Auth, verify `HYTALE_TRUST_ALL_ISSUERS=true` on the server
3. If using federated auth, verify your JWKS endpoint is publicly reachable: `curl https://your-domain/.well-known/jwks.json`
4. Check token expiration -- expired tokens are rejected

### "JWKS fetch failed"

The game server couldn't reach your auth server's `/.well-known/jwks.json` endpoint:
- Verify DNS resolves correctly
- Verify HTTPS certificate is valid
- Verify the endpoint returns valid JSON with `keys` array

### "Issuer not trusted"

The server has `HYTALE_TRUST_ALL_ISSUERS=false` and your issuer URL is not in `HYTALE_TRUSTED_ISSUERS`. Ask the server operator to add your issuer URL.

### Agent not loading

- Verify `dualauth-agent.jar` exists in the expected path
- Verify the `-javaagent:` flag is in the Java command
- Enable debug: set `DUALAUTH_LOGGING_ENABLED=true` or `-Ddualauth.debug=true`

### Player names showing as "Player"

The default username `Player` is used when no username is provided in the token. Include `username`, `name`, or `nickname` in your JWT payload claims.

---

## Community

- **Discord**: https://discord.gg/gME8rUy3MB
- **GitHub Issues**: https://github.com/sanasol/hytale-auth-server/issues
- **Server List**: https://santale.top

## Links

| Resource | URL |
|----------|-----|
| DualAuth Agent Source | https://github.com/sanasol/hytale-auth-server (dualauth-agent/) |
| Auth Server Source | https://github.com/sanasol/hytale-auth-server |
| Docker Game Server | https://github.com/sanasol/hytale-server-docker |
| F2P Launcher | https://github.com/sanasol/Hytale-F2P |
| Omni-Auth Spec | [OMNI_AUTH.md](OMNI_AUTH.md) |
| RusTale (original Omni-Auth) | https://github.com/soyelmismo/RusTale |
