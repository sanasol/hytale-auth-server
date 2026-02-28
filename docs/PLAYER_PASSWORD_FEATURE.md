# Per-Player Password Authentication

## Overview

Opt-in password protection for F2P player UUIDs. Without a password, anyone who knows a UUID can impersonate that player (connect as them, access inventory, use permissions). With a password set, the auth server gates token generation behind bcrypt verification with rate limiting.

## How It Works

### Launch Flow (password-protected UUID)

```
┌──────────────────────────────────────────────────────────────────────┐
│  1. Launcher calls GET /player/password/status/{uuid}                │
│  2. Auth server returns { hasPassword: true, registeredName: "..." } │
│  3. Launcher prompts user for password                               │
│  4. Launcher calls POST /game-session/new with { password: "..." }   │
│  5. Auth server verifies bcrypt hash                                 │
│     - Correct → generate tokens normally                             │
│     - Wrong   → 401 { password_required, attemptsRemaining }        │
│     - Locked  → 429 { lockoutSeconds }                               │
│  6. Player connects to game server with valid tokens                 │
│  7. Game server calls /game-session/child, /auth-grant, etc.         │
│     with Bearer token → auth server verifies Ed25519 signature       │
│     and allows (no password needed — token proves prior auth)        │
└──────────────────────────────────────────────────────────────────────┘
```

### Token Chain of Trust

All session endpoints use `requireAuth()` which accepts **either**:
1. **Valid Bearer token** — Ed25519 signature-verified JWT with matching `sub` (proves player already authenticated with password)
2. **Correct password** in request body (direct launcher call)

This means the game client/server never needs to know the password — they pass the Bearer token obtained during the initial `game-session/new` call. The auth server cryptographically verifies the token before issuing new tokens.

---

## API Reference (for Launcher Developers)

### Password Status

Check if a UUID has password protection.

```
GET /player/password/status/{uuid}
```

**Response** `200`:
```json
{
  "hasPassword": true,
  "registeredName": "Sanasol"
}
```

- `hasPassword` — `true` if the UUID requires a password to generate tokens
- `registeredName` — The username locked to this UUID (only present if password is set). The UUID can only generate tokens for this name.

---

### Set / Change Password

Set a new password or change an existing one. Requires a Bearer token whose `sub` matches the UUID.

```
POST /player/password/set
Authorization: Bearer <identityToken>
Content-Type: application/json
```

**Body:**
```json
{
  "uuid": "adfd7538-edba-459d-a950-05a704e4f42a",
  "password": "newpassword123",
  "currentPassword": "oldpassword"
}
```

- `password` — New password (min 6 characters)
- `currentPassword` — Required only when changing an existing password
- `username` — Optional, falls back to Bearer token's `name` claim. Reserved for this UUID.

**Responses:**

| Status | Body | Condition |
|--------|------|-----------|
| `200` | `{ success: true, username_reserved: true, reserved_username: "Sanasol" }` | Password set, username reserved |
| `200` | `{ success: true, username_reserved: false, username_taken_by: "another player" }` | Password set, but username already reserved by another UUID |
| `400` | `{ error: "Password must be at least 6 characters" }` | Password too short |
| `400` | `{ error: "Current password required to change password" }` | Changing password without `currentPassword` |
| `401` | `{ error: "Current password is incorrect", attemptsRemaining: 4 }` | Wrong current password |
| `403` | `{ error: "Token UUID does not match requested UUID" }` | Bearer token `sub` doesn't match `uuid` |
| `429` | `{ error: "Too many failed attempts", lockoutSeconds: 900 }` | Account locked out |
| `429` | `{ error: "Too many password operations from this IP", retryAfter: 3600 }` | IP rate limited (20 per hour) |

---

### Remove Password

Remove password protection from a UUID. Requires Bearer token and current password.

```
POST /player/password/remove
Authorization: Bearer <identityToken>
Content-Type: application/json
```

**Body:**
```json
{
  "uuid": "adfd7538-edba-459d-a950-05a704e4f42a",
  "currentPassword": "mypassword"
}
```

**Responses:**

| Status | Body | Condition |
|--------|------|-----------|
| `200` | `{ success: true }` | Password removed, username reservation released |
| `400` | `{ error: "No password set for this UUID" }` | UUID has no password |
| `400` | `{ error: "Current password required" }` | Missing `currentPassword` |
| `401` | `{ error: "Current password is incorrect", attemptsRemaining: N }` | Wrong password |
| `403` | `{ error: "Token UUID does not match requested UUID" }` | Token mismatch |
| `429` | `{ error: "Too many failed attempts", lockoutSeconds: 900 }` | Locked out |

---

### Username Reservation Status

Check if a username is reserved by another player.

```
GET /player/username/status/{username}
```

**Response** `200`:
```json
{
  "reserved": true
}
```

---

### Check Identity (for DualAuth Agent)

Used by the DualAuth agent to check if a UUID/username is protected before allowing Omni-Auth connections.

```
GET /api/check-identity?uuid=...&username=...
```

**Responses:**

| Status | Body | Condition |
|--------|------|-----------|
| `200` | `{ allowed: true }` | UUID is not protected, username is not reserved |
| `200` | `{ allowed: false, reason: "password_protected" }` | UUID has a password set |
| `200` | `{ allowed: false, reason: "username_reserved", owner_uuid: "..." }` | Username reserved by a different UUID |

---

### Create Game Session (with password)

The primary token generation endpoint. Protected by `requireAuth()`.

```
POST /game-session/new
Content-Type: application/json
```

**Body:**
```json
{
  "uuid": "adfd7538-edba-459d-a950-05a704e4f42a",
  "name": "Sanasol",
  "password": "mypassword",
  "scopes": ["hytale:client", "hytale:server"]
}
```

- `password` — Required if UUID has password protection. Not needed if a valid Bearer token is present.
- `POST /game-session` is an alias for this endpoint.

**Success** `200`:
```json
{
  "identityToken": "eyJ...",
  "sessionToken": "eyJ...",
  "expiresIn": 36000,
  "expiresAt": "2026-02-28T12:00:00.000Z",
  "tokenType": "Bearer"
}
```

**Error responses:**

| Status | Body | Condition |
|--------|------|-----------|
| `401` | `{ error: "Password required", password_required: true, attemptsRemaining: 5 }` | UUID is protected, no/wrong password, no valid Bearer |
| `403` | `{ error: "This username is reserved by another player", username_taken: true }` | Name belongs to another UUID |
| `403` | `{ error: "This UUID is locked to username \"Sanasol\"", name_locked: true, registeredName: "Sanasol" }` | Protected UUID trying to use a different name |
| `429` | `{ error: "Too many failed attempts. Try again later.", lockoutSeconds: 900 }` | Account locked after 5 failures |

---

### Other Protected Endpoints

All token-issuing endpoints use the same `requireAuth()` check. Game clients/servers pass their Bearer token in the `Authorization` header (obtained from the initial session), which is signature-verified:

| Endpoint | Method | Notes |
|----------|--------|-------|
| `/game-session/new` | POST | Launcher calls this with password |
| `/game-session` | POST | Alias for `/game-session/new` |
| `/game-session/refresh` | POST | Game client sends Bearer token |
| `/game-session/child` | POST | Game client sends Bearer token |
| `/game-session/authorize` | POST | Server sends Bearer + identity token in body |
| `/server-join/auth-grant` | POST | Server sends Bearer + identity token in body |
| `/server-join/auth-token` | POST | Server sends Bearer + auth grant in body |
| Catch-all (unknown paths) | POST | Also protected — prevents bypass via unknown routes |

**Generic fallback endpoints** (also protected):
- Any path containing `/session`, `/auth`, `/token`, `/validate`, `/verify`, `/refresh`

---

### Admin Endpoints

Requires `x-admin-token` header matching `ADMIN_PASSWORD` env var.

```
GET /admin/api/players/{uuid}/password-status
```
```json
{
  "hasPassword": true,
  "attemptCount": 2,
  "reservedUsername": "Sanasol"
}
```

```
DELETE /admin/api/players/{uuid}/password
```
```json
{
  "success": true
}
```

Removes password, clears lockout counter, and releases username reservation.

---

## Security Details

### Password Storage
- **Algorithm**: bcrypt (bcryptjs, pure JS, no native deps)
- **Rounds**: 12
- **Storage**: Redis key `playerpassword:{uuid}` → bcrypt hash string

### Rate Limiting
- **Per-UUID**: 5 wrong passwords → 15-minute lockout (`pwattempts:{uuid}`)
- **Per-IP**: 20 password-set operations per hour (`pwset_ratelimit:{ip}`)
- **Timing attack mitigation**: `bcrypt.compare()` always runs, even when no password exists (dummy hash)

### Token Verification
- `verifyToken()` performs full Ed25519 signature verification + expiry check
- `parseToken()` only decodes (no signature check) — used for non-critical data extraction
- Bearer tokens in Authorization headers are always signature-verified before granting access

### Name Lock
- Password-protected UUIDs can only generate tokens for their registered username
- Prevents impersonation even if someone knows the UUID + password
- Name changes require re-setting the password (which re-reserves the new name)

### Username Reservation
- Case-insensitive: "Player1" and "player1" are the same
- One username per UUID, one UUID per username
- Automatically reserved when password is set
- Automatically released when password is removed
- Audit log stored in Redis (last 1000 entries)

---

## Redis Key Reference

| Key Pattern | Type | TTL | Purpose |
|-------------|------|-----|---------|
| `playerpassword:{uuid}` | String (bcrypt hash) | None | Stored password hash |
| `pwattempts:{uuid}` | String (integer) | 900s | Failed attempt counter |
| `pwset_ratelimit:{ip}` | String (integer) | 3600s | IP rate limit for password-set |
| `username_reserved:{lowercase_name}` | String (JSON) | None | `{ uuid, username, ip, reservedAt }` |
| `uuid_username:{uuid}` | String (username) | None | Maps UUID → reserved username |
| `username_audit` | List (JSON entries) | None | Last 1000 audit log entries |

---

## Launcher Integration Guide

### Recommended Flow

```
1. User selects identity (UUID + username)
2. GET /player/password/status/{uuid}
   ├── hasPassword: false → proceed to step 4
   └── hasPassword: true
       ├── Check savedPasswords[uuid] for remembered password
       │   ├── Found → use it, proceed to step 4
       │   └── Not found → prompt user for password
       └── registeredName differs from selected name?
           └── Show warning: "This UUID is locked to {registeredName}"
3. User enters password (or uses remembered)
4. POST /game-session/new { uuid, name, password? }
   ├── 200 → save tokens, launch game
   ├── 401 password_required → show password prompt
   ├── 403 username_taken → show "username reserved" error
   ├── 403 name_locked → show "UUID locked to {registeredName}" error
   └── 429 → show "locked out, try again in {lockoutSeconds}s"
5. Game client uses Bearer token for all subsequent API calls
   (no password needed — token proves prior auth)
```

### Password Management UI

```
Set Password:
  POST /player/password/set
  Headers: Authorization: Bearer <token from game-session/new>
  Body: { uuid, password }
  → On success: show lock icon, refresh identity list

Change Password:
  POST /player/password/set
  Headers: Authorization: Bearer <token>
  Body: { uuid, password: <new>, currentPassword: <old> }

Remove Password:
  POST /player/password/remove
  Headers: Authorization: Bearer <token>
  Body: { uuid, currentPassword }
  → On success: show unlock icon, refresh identity list

Delete Protected Identity:
  1. POST /player/password/remove (with currentPassword)
  2. Delete identity locally
  → Must remove server-side password BEFORE local deletion

Restore Protected Identity:
  1. GET /player/password/status/{uuid} → check hasPassword
  2. If protected: prompt for password
  3. POST /game-session/new { uuid, name, password } → verify password works
  4. If name_locked: use registeredName instead of entered name
  5. Save identity locally with force=true (overwrite any existing entry)
```

---

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
# → 200 { success: true, username_reserved: true }

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

# 5. Wrong name with locked UUID
curl -X POST localhost:3000/game-session/new \
  -H 'Content-Type: application/json' \
  -d '{"uuid":"test-uuid","name":"DifferentName","password":"secret123"}'
# → 403 { error: "This UUID is locked to username \"TestPlayer\"", name_locked: true }

# 6. Wrong password (repeat 5 times)
curl -X POST localhost:3000/game-session/new \
  -H 'Content-Type: application/json' \
  -d '{"uuid":"test-uuid","name":"TestPlayer","password":"wrong"}'
# → 401 { attemptsRemaining: 4 } ... → 429 { lockoutSeconds: 900 }

# 7. Check status
curl localhost:3000/player/password/status/test-uuid
# → { hasPassword: true, registeredName: "TestPlayer" }

# 8. Check username reservation
curl localhost:3000/player/username/status/TestPlayer
# → { reserved: true }

# 9. Check identity (for agent)
curl "localhost:3000/api/check-identity?uuid=test-uuid&username=TestPlayer"
# → { allowed: false, reason: "password_protected" }

# 10. Admin reset
curl -X DELETE localhost:3000/admin/api/players/test-uuid/password \
  -H 'x-admin-token: <ADMIN_PASSWORD>'
# → { success: true }
```

---

## Known Bypass Vectors & Mitigations

### The Core Problem

Password protection gates token generation on `auth.sanasol.ws`, but the DualAuth agent accepts tokens from **multiple sources**. An attacker does not need to go through `auth.sanasol.ws` at all — they can generate their own valid tokens and connect directly to any game server running the agent.

### Attack Vector 1: Omni-Auth Self-Signed Tokens (Critical)

**Severity**: Critical — completely bypasses password protection

The DualAuth agent supports **Omni-Auth**: tokens that carry their own Ed25519 public key in the JWT `jwk` header. The agent verifies the signature against that embedded key — meaning **any self-signed token is valid**.

**Mitigation**: Agent v1.1.16 includes identity protection checking. When `HYTALE_AUTH_DOMAIN` is set, the agent calls `GET /api/check-identity?uuid=...&username=...` for Omni-Auth tokens and rejects those targeting password-protected UUIDs/usernames.

### Attack Vector 2: Self-Hosted Auth Server JWKS Spoofing

**Severity**: High — bypasses password protection when `TRUST_ALL_ISSUERS=true`

**Mitigation**: Set `HYTALE_TRUST_ALL_ISSUERS=false` and explicitly list trusted issuers.

### Current Limitation

Password protection is strongest when:
1. Server runs with `HYTALE_TRUST_ALL_ISSUERS=false`
2. Agent v1.1.16+ is used (identity protection check for Omni-Auth)
3. Only `auth.sanasol.ws` and `sessions.hytale.com` are trusted

---

## Implementation Files

| File | Purpose |
|------|---------|
| `src/services/password.js` | Password hashing, verification, rate limiting, username reservation |
| `src/services/auth.js` | `verifyToken()` (Ed25519 signature verification), `parseToken()` (decode only) |
| `src/routes/player.js` | Password status/set/remove endpoints, check-identity API |
| `src/routes/session.js` | `requireAuth()` guard on all token-issuing endpoints |
| `src/routes/admin.js` | Admin password management endpoints |
| `src/routes/account.js` | `password_protected` flag in game profile response |
| `src/app.js` | Route registration, catch-all protection |
| `src/config/index.js` | Redis key prefixes |

## Notes

- Password auth only affects F2P token generation — official JWT-authenticated players are unaffected
- Password is per-UUID, not per-server — works across all F2P servers
- Admin can always reset a player's password via the admin dashboard or API
- Launcher checks password status before launching to prompt early, avoiding mid-launch failures
