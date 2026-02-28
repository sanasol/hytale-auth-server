# Per-Player Password Authentication

## Overview

Opt-in password protection for F2P player UUIDs. Without a password, anyone who knows a UUID can impersonate that player (connect as them, access inventory, use permissions). With a password set, the auth server gates token generation behind bcrypt verification with rate limiting.

## How It Works

```
┌──────────────────────────────────────────────────────────────────────┐
│  LAUNCH FLOW (password-protected UUID)                               │
│                                                                      │
│  1. Launcher calls GET /player/password/status/{uuid}                │
│  2. Auth server returns { hasPassword: true }                        │
│  3. Launcher prompts user for password                               │
│  4. Launcher calls POST /game-session/child with { password: "..." } │
│  5. Auth server verifies bcrypt hash                                 │
│     - Correct → generate tokens normally                             │
│     - Wrong   → 401 { password_required, attemptsRemaining }        │
│     - Locked  → 429 { lockoutSeconds }                               │
│  6. Player connects to game server with valid tokens                 │
└──────────────────────────────────────────────────────────────────────┘
```

## API Endpoints

### Public

| Endpoint | Method | Auth | Description |
|----------|--------|------|-------------|
| `/player/password/status/{uuid}` | GET | None | Returns `{ hasPassword: bool }` |
| `/player/username/status/{username}` | GET | None | Returns `{ reserved: bool }` — check if username is taken |

### Player (requires Bearer token with matching `sub`)

| Endpoint | Method | Body | Description |
|----------|--------|------|-------------|
| `/player/password/set` | POST | `{ uuid, password, currentPassword? }` | Set or change password (min 6 chars) |
| `/player/password/remove` | POST | `{ uuid, currentPassword }` | Remove password protection |

### Admin (requires admin token)

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/admin/api/players/{uuid}/password-status` | GET | Returns `{ hasPassword, attemptCount, reservedUsername }` |
| `/admin/api/players/{uuid}/password` | DELETE | Remove password + clear lockout |

### Modified Endpoints

| Endpoint | Change |
|----------|--------|
| `POST /game-session/new` | Accepts optional `password` field; returns 401/429 if UUID is protected; returns 403 if username is reserved by another UUID |
| `POST /game-session/child` | Same as above |
| `GET /my-account/game-profile` | Response includes `password_protected: bool` |

## Security

### Password Storage
- **Algorithm**: bcrypt (bcryptjs, pure JS, no native deps)
- **Rounds**: 12 (configurable in `src/services/password.js`)
- **Storage**: Redis key `playerpassword:{uuid}` → bcrypt hash string

### Rate Limiting
- **Max attempts**: 5 per UUID
- **Lockout duration**: 15 minutes
- **Storage**: Redis key `pwattempts:{uuid}` → integer counter with TTL
- **Behavior**: After 5 wrong passwords → 429 response until TTL expires

### Timing Attack Mitigation
- `verifyPassword()` always runs `bcrypt.compare()` even when no password exists (uses dummy hash)
- Prevents timing-based enumeration of password-protected vs unprotected UUIDs

## Implementation Files

### Auth Server

| File | Changes |
|------|---------|
| `package.json` | Added `bcryptjs` dependency |
| `src/config/index.js` | Added `PLAYER_PASSWORD` and `PASSWORD_ATTEMPTS` Redis key prefixes |
| `src/services/password.js` | **New** — `hasPassword()`, `verifyPassword()`, `setPassword()`, `removePassword()`, `resetAttempts()`, `getAttemptCount()` |
| `src/routes/player.js` | **New** — password status/set/remove endpoints |
| `src/routes/session.js` | `handleGameSessionNew` and `handleGameSessionChild` now async with password gate |
| `src/routes/account.js` | `handleGameProfile` returns `password_protected` flag |
| `src/routes/admin.js` | Admin password status + reset endpoints |
| `src/routes/adminPages.js` | "Reset PW" button on player list rows |
| `src/routes/index.js` | Registered `player` routes |
| `src/app.js` | Registered all new routes, `await`ed async session handlers |

### DualAuth Agent (v1.1.14)

| File | Changes |
|------|---------|
| `context/DualAuthContext.java` | Added `PlayerAuthInfo` record class and `ConcurrentHashMap` registry with static API: `registerPlayer()`, `unregisterPlayer()`, `getPlayerInfo()`, `isPlayerF2P()`, `isPlayerOfficial()`, `getOnlinePlayers()` |
| `transformers/JWTValidatorTransformer.java` | Populates registry in `ValidateAdvice.exit()` after successful validation |
| `transformers/HandshakeHandlerTransformer.java` | Calls `unregisterPlayer()` before context reset on new connection |
| `build.gradle` | Version bumped to `1.1.14` |

#### Mod API Usage

```java
// Check if a player is F2P
boolean isF2P = DualAuthContext.isPlayerF2P(uuid);

// Check if a player is official
boolean isOfficial = DualAuthContext.isPlayerOfficial(uuid);

// Get detailed auth info
DualAuthContext.PlayerAuthInfo info = DualAuthContext.getPlayerInfo(uuid);
if (info != null) {
    System.out.println("Player: " + info.username);
    System.out.println("Issuer: " + info.issuer);
    System.out.println("F2P: " + info.isF2P);
    System.out.println("Omni: " + info.isOmni);
    System.out.println("Auth time: " + info.authenticatedAt);
}

// Get all online players
Map<String, DualAuthContext.PlayerAuthInfo> players = DualAuthContext.getOnlinePlayers();
```

### F2P Launcher

| File | Changes |
|------|---------|
| `preload.js` | Added `checkPasswordStatus`, `setPlayerPassword`, `removePlayerPassword`, `launchGameWithPassword`, `onPasswordPrompt` IPC bridges |
| `GUI/index.html` | Password protection section in UUID modal (status, set/change/remove form) |
| `GUI/js/settings.js` | `togglePasswordSection()`, `refreshPasswordStatus()`, `handleSetPassword()`, `handleRemovePassword()` |
| `GUI/js/launcher.js` | `promptForPassword()` dialog; handles `passwordRequired` from backend; retries via `launchGameWithPassword` |
| `main.js` | IPC handlers for password CRUD + pre-launch password check + `launch-game-with-password` handler |
| `backend/managers/gameLauncher.js` | `fetchAuthTokens()` accepts password param; structured 401/429 error handling; `options.password` forwarding through launch chain |

## Redis Keys

| Key Pattern | Type | TTL | Purpose |
|-------------|------|-----|---------|
| `playerpassword:{uuid}` | String (bcrypt hash) | None | Stored password hash |
| `pwattempts:{uuid}` | String (integer) | 900s (15 min) | Failed attempt counter |
| `username_reserved:{lowercase_name}` | String (UUID) | None | Maps reserved username → owner UUID |
| `uuid_username:{uuid}` | String (username) | None | Maps UUID → reserved username (for cleanup) |

## Username Reservation

When a player sets a password, their current username is automatically reserved for their UUID. This prevents other players from impersonating them by using the same display name with a different UUID.

### How It Works

1. Player sets password via `/player/password/set` → username from their Bearer token is reserved
2. Another player tries to create a session with that username but a different UUID → **403 Forbidden** `{ username_taken: true }`
3. Player removes password → username reservation is released
4. Admin removes password → username reservation is also released
5. If a player changes their username (sets password again with new token), the old reservation is released and the new name is reserved

### Behavior

- Case-insensitive: "Player1" and "player1" are treated as the same username
- One username per UUID: setting a new password with a different name releases the old reservation
- One UUID per username: if two password-protected UUIDs try to reserve the same name, the second one gets a warning
- Unprotected players (no password) are not affected by reservations — they can still use any unreserved name
- Reservation only blocks token generation, not in-game display name changes by mods

## Testing

```bash
# 1. No password — should work
curl -X POST localhost:3000/game-session/new \
  -H 'Content-Type: application/json' \
  -d '{"uuid":"test-uuid","name":"TestPlayer"}'
# → 200 with tokens

# 2. Set password (need bearer token first from step 1)
curl -X POST localhost:3000/player/password/set \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer <identityToken from step 1>' \
  -d '{"uuid":"test-uuid","password":"secret123"}'
# → 200 { success: true }

# 3. Anonymous blocked
curl -X POST localhost:3000/game-session/new \
  -H 'Content-Type: application/json' \
  -d '{"uuid":"test-uuid","name":"TestPlayer"}'
# → 401 { error: "Password required", password_required: true, attemptsRemaining: 5 }

# 4. With password
curl -X POST localhost:3000/game-session/new \
  -H 'Content-Type: application/json' \
  -d '{"uuid":"test-uuid","name":"TestPlayer","password":"secret123"}'
# → 200 with tokens

# 5. Wrong password (repeat 5 times)
curl -X POST localhost:3000/game-session/new \
  -H 'Content-Type: application/json' \
  -d '{"uuid":"test-uuid","name":"TestPlayer","password":"wrong"}'
# → 401 { attemptsRemaining: 4 } ... → 429 { lockoutSeconds: 900 }

# 6. Check status
curl localhost:3000/player/password/status/test-uuid
# → { hasPassword: true }

# 7. Admin reset
curl -X DELETE localhost:3000/admin/api/players/test-uuid/password \
  -H 'x-admin-token: <token>'
# → { success: true }

# 8. Profile flag
curl -X POST localhost:3000/my-account/game-profile \
  -H 'Content-Type: application/json' \
  -d '{"uuid":"test-uuid","name":"TestPlayer"}'
# → { ..., password_protected: false }
```

## Known Bypass Vectors & Mitigations

### The Core Problem

Password protection gates token generation on `auth.sanasol.ws`, but the DualAuth agent accepts tokens from **multiple sources**. An attacker does not need to go through `auth.sanasol.ws` at all — they can generate their own valid tokens and connect directly to any game server running the agent.

### Attack Vector 1: Omni-Auth Self-Signed Tokens (Critical)

**Severity**: Critical — completely bypasses password protection

The DualAuth agent supports **Omni-Auth**: tokens that carry their own Ed25519 public key in the JWT `jwk` header. The agent verifies the signature against that embedded key — meaning **any self-signed token is valid**.

**Attack flow**:
1. Attacker generates their own Ed25519 keypair
2. Creates a JWT with `sub: <victim-uuid>`, `username: <victim-name>`, embeds the public key in the header
3. Signs it with their private key
4. Connects to any game server running DualAuth
5. `EmbeddedJwkVerifier.verifyAndGetClaims()` validates signature against the embedded key — passes
6. `isOmniIssuerTrusted()` returns `true` because **`TRUST_ALL_ISSUERS` defaults to `true`**
7. Player is authenticated as the victim — password check never happens

**Code path**: `EmbeddedJwkVerifier.java:64` → `DualAuthHelper.isOmniIssuerTrusted()` → `DualAuthConfig.TRUST_ALL_ISSUERS` (default `true`)

### Attack Vector 2: Self-Hosted Auth Server JWKS Spoofing

**Severity**: High — bypasses password protection when `TRUST_ALL_ISSUERS=true`

Even without Omni-Auth, the agent's `isValidIssuer()` returns `true` for **any** issuer when `TRUST_ALL_ISSUERS=true`.

**Attack flow**:
1. Attacker runs their own auth server at `evil.com` with `/.well-known/jwks.json`
2. Generates tokens with `iss: https://evil.com`, `sub: <victim-uuid>`
3. Game server's DualAuth agent fetches JWKS from `evil.com`, validates token — passes
4. Player is authenticated as the victim

### Attack Vector 3: Weak Issuer String Matching

**Severity**: Medium — exploitable even with `TRUST_ALL_ISSUERS=false`

`isOmniIssuerTrusted()` uses bidirectional substring matching:
```java
if (norm.contains(t) || t.contains(norm)) return true;
```

An attacker with domain `sanasol.ws.evil.com` would match trusted issuer `sanasol.ws`. Similarly `evil-sanasol.ws` or any domain containing the substring.

### Mitigation Options

| Option | Protection | Breaking Change | Complexity | Status |
|--------|-----------|-----------------|------------|--------|
| **A. Default `TRUST_ALL_ISSUERS=false`** | Blocks unknown JWKS issuers, Omni-Auth still open | Yes — breaks self-hosted setups | Low | Not implemented |
| **B. Block Omni-Auth for protected UUIDs** | Agent calls auth server to check if UUID has password; rejects Omni-Auth tokens for protected UUIDs | No | Medium | Not implemented |
| **C. Server-side verification callback** | Game server calls auth API on every connect for protected UUIDs to verify the token was actually issued by auth.sanasol.ws | No | Medium | Not implemented |
| **D. Key pinning / issuer allowlist** | Only accept tokens signed by known JWKS keys (no Omni-Auth at all) | Yes — breaks Omni-Auth | Low | Not implemented |
| **E. Cached protected-UUID set** | Auth server exposes `GET /player/password/protected-uuids` endpoint; agent fetches periodically (e.g., every 60s) and blocks Omni-Auth + untrusted JWKS for any UUID in the set | No | Medium | Not implemented |
| **F. Fix issuer string matching** | Use exact domain matching instead of substring `contains()` | Minimal | Low | Not implemented |

### Recommended Approach: E + F

**Option E** (cached protected-UUID set) provides the best balance:
- Auth server exposes a list of password-protected UUIDs (or a bloom filter / hash set)
- Agent caches this list with a configurable TTL (e.g., 60s)
- During token validation, if UUID is in the protected set:
  - Omni-Auth tokens are **rejected** (must go through real auth server)
  - JWKS tokens are only accepted from the **configured F2P issuer** (`auth.sanasol.ws`)
  - Official tokens (`sessions.hytale.com`) are always accepted regardless
- One periodic HTTP call instead of one per connection — minimal performance impact

**Option F** (fix string matching) should be done regardless — it's a simple fix that closes a real hole.

### Current Limitation

**Password protection currently only works as a social/convenience feature** — it prevents casual impersonation by users who go through the official F2P launcher, but does not protect against technically skilled attackers who craft their own tokens. Full protection requires implementing Option E or similar agent-side enforcement.

## Notes

- Password auth only affects F2P token generation — official JWT-authenticated players are unaffected
- Password is per-UUID, not per-server — works across all F2P servers
- Admin can always reset a player's password via the admin dashboard or API
- The DualAuth agent's `PlayerAuthInfo` registry is in-memory only (not persisted) — clears on server restart
- Launcher checks password status before launching to prompt early, avoiding mid-launch failures
