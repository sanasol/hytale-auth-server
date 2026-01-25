# [TODO] Per-Player Password Authentication Feature

## Overview

Allow each player to set their own password to protect their account from UUID spoofing in insecure/F2P mode. Without this, anyone who knows a player's UUID can connect as them.

## Problem

In insecure mode (no JWT authentication), the server trusts the UUID and username sent by the client. An attacker can:
1. Find a player's UUID (from server lists, logs, etc.)
2. Connect with that UUID and impersonate the player
3. Access their inventory, permissions, builds, etc.

## Solution

Per-player passwords stored on the F2P auth server, verified by the game server via API call.

## Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              PLAYER FLOW                                     │
│                                                                             │
│  1. Player sets password via launcher or web portal                         │
│  2. Password hash stored in Redis: playerpassword:{uuid} -> hash            │
│  3. When connecting to server, player enters password in client             │
│  4. Server sends challenge to client                                        │
│  5. Client computes hash(challenge + password) and sends back               │
│  6. Server calls auth API to verify: POST /player/verify-password           │
│  7. Auth server verifies hash against stored password                       │
│  8. If valid, player connects; if invalid, connection rejected              │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

## Components to Modify

### 1. F2P Auth Server (sanasol.ws)

**File:** `/Users/sanasol/code/pterodactyl-hytale/hytale-auth-server/server.js`
**Also:** `/Users/sanasol/code/pterodactyl-hytale/traefik/hytale-auth/server.js`

#### New Redis Key
```javascript
// Add to REDIS_KEYS object (line ~42-50)
PLAYER_PASSWORD: 'playerpassword:', // playerpassword:{uuid} -> bcrypt hash
```

#### New API Endpoints

```javascript
// POST /player/set-password
// Sets or updates player password
// Body: { uuid: "...", password: "plaintext" }
// Response: { success: true }
// Note: Should require authentication (bearer token with matching UUID)

// POST /player/verify-password
// Verifies password for server authentication
// Body: { uuid: "...", challenge: "base64...", responseHash: "base64..." }
// Response: { valid: true/false }
// Note: Server calls this, so need server auth or rate limiting

// DELETE /player/password
// Removes password (allows passwordless login again)
// Body: { uuid: "..." }
// Response: { success: true }

// GET /player/has-password/{uuid}
// Check if player has password set (for UI)
// Response: { hasPassword: true/false }
```

#### Password Verification Logic

```javascript
// Server sends: challenge (32 random bytes, base64)
// Client computes: SHA256(challenge + password) -> responseHash (base64)
// Auth server computes: SHA256(challenge + storedPassword) -> expectedHash
// Compare: responseHash === expectedHash

async function verifyPlayerPassword(uuid, challenge, responseHash) {
  const storedHash = await redis.get(`${REDIS_KEYS.PLAYER_PASSWORD}${uuid}`);
  if (!storedHash) {
    // No password set - allow connection (or deny based on config)
    return { valid: true, noPassword: true };
  }

  // Decrypt/retrieve actual password from stored hash
  // Compute expected hash
  const expectedHash = crypto
    .createHash('sha256')
    .update(Buffer.from(challenge, 'base64'))
    .update(storedPassword) // Need to store password in recoverable form for challenge-response
    .digest('base64');

  return { valid: responseHash === expectedHash };
}
```

**Important:** The current server uses challenge-response (not sending password directly), which means we need to store passwords in a way we can use them for verification. Options:
1. Store password encrypted (not hashed) - can decrypt for challenge-response
2. Change protocol to send password hash directly over TLS (simpler, still secure)
3. Use SRP (Secure Remote Password) protocol - more complex but most secure

### 2. Game Server JAR (Bytecode Patch)

**Patcher File:** `hytale-auth-server/patcher/DualAuthPatcher.java` (authoritative source)

**Target Classes to Patch:**

#### PasswordPacketHandler.java
**Location:** `/Users/sanasol/Downloads/Hytale_Decompiled/Server_Code/com/hypixel/hytale/server/core/io/handlers/login/PasswordPacketHandler.java`

**Current behavior (line 110-154):**
```java
public void handle(@Nonnull PasswordResponse packet) {
  // ...
  String password = HytaleServer.get().getConfig().getPassword(); // Gets GLOBAL password
  byte[] expectedHash = computePasswordHash(this.passwordChallenge, password);
  // Compares client hash with expected hash
}
```

**Patch needed:**
- Instead of `HytaleServer.get().getConfig().getPassword()`
- Call external API: `POST https://sessions.sanasol.ws/player/verify-password`
- Pass: `{ uuid: this.playerUuid, challenge: base64(this.passwordChallenge), responseHash: base64(clientHash) }`

#### HandshakeHandler.java
**Location:** `/Users/sanasol/Downloads/Hytale_Decompiled/Server_Code/com/hypixel/hytale/server/core/io/handlers/login/HandshakeHandler.java`

**Current behavior (line 362-378):**
```java
private byte[] generatePasswordChallengeIfNeeded() {
  String password = HytaleServer.get().getConfig().getPassword();
  if (password != null && !password.isEmpty()) {
    // Generate challenge
  }
  return null;
}
```

**Patch needed:**
- Check if player has password set via API: `GET https://sessions.sanasol.ws/player/has-password/{uuid}`
- If yes, generate challenge regardless of global password setting

#### InitialPacketHandler.java
**Location:** `/Users/sanasol/Downloads/Hytale_Decompiled/Server_Code/com/hypixel/hytale/server/core/io/handlers/InitialPacketHandler.java`

**Current behavior (line 220-236):**
```java
private byte[] generatePasswordChallengeIfNeeded(UUID playerUuid) {
  String password = HytaleServer.get().getConfig().getPassword();
  // Same logic as HandshakeHandler
}
```

**Patch needed:** Same as HandshakeHandler

### 3. F2P Launcher (Optional - for password setup UI)

**Directory:** `/Users/sanasol/code/pterodactyl-hytale/f2p/`

Add UI for:
- Setting password
- Changing password
- Removing password

## Implementation Steps

### Phase 1: Auth Server API

1. Add `PLAYER_PASSWORD` to `REDIS_KEYS`
2. Implement `POST /player/set-password` endpoint
3. Implement `POST /player/verify-password` endpoint
4. Implement `GET /player/has-password/{uuid}` endpoint
5. Implement `DELETE /player/password` endpoint
6. Add rate limiting to prevent brute force

### Phase 2: Server JAR Patch

1. Create `PlayerPasswordHelper` class to inject:
   ```java
   public final class PlayerPasswordHelper {
     public static final String AUTH_URL = "https://sessions.sanasol.ws";

     public static boolean hasPassword(UUID uuid) { ... }
     public static boolean verifyPassword(UUID uuid, byte[] challenge, byte[] responseHash) { ... }
   }
   ```

2. Patch `PasswordPacketHandler.handle()`:
   - Replace password verification with `PlayerPasswordHelper.verifyPassword()`

3. Patch `generatePasswordChallengeIfNeeded()` in both handlers:
   - Add check for `PlayerPasswordHelper.hasPassword(uuid)`

### Phase 3: Launcher UI (Optional)

1. Add "Set Server Password" option in settings
2. Password input with confirmation
3. API call to set password
4. Show password status (set/not set)

## File References

| Component | File Path |
|-----------|-----------|
| Auth Server (main) | `hytale-auth-server/src/app.js` |
| Auth Server (traefik) | `traefik/hytale-auth/` (uses same source) |
| JAR Patcher | `hytale-auth-server/patcher/DualAuthPatcher.java` (authoritative source) |
| Decompiled PasswordPacketHandler | `/Users/sanasol/Downloads/Hytale_Decompiled/Server_Code/com/hypixel/hytale/server/core/io/handlers/login/PasswordPacketHandler.java` |
| Decompiled HandshakeHandler | `/Users/sanasol/Downloads/Hytale_Decompiled/Server_Code/com/hypixel/hytale/server/core/io/handlers/login/HandshakeHandler.java` |
| Decompiled InitialPacketHandler | `/Users/sanasol/Downloads/Hytale_Decompiled/Server_Code/com/hypixel/hytale/server/core/io/handlers/InitialPacketHandler.java` |
| Decompiled HytaleServerConfig | `/Users/sanasol/Downloads/Hytale_Decompiled/Server_Code/com/hypixel/hytale/server/core/HytaleServerConfig.java` |
| F2P Launcher | `/Users/sanasol/code/pterodactyl-hytale/f2p/` |
| Dual Auth Doc | `/Users/sanasol/code/pterodactyl-hytale/DUAL_AUTH_IMPLEMENTATION.md` |

## Protocol Packets

| Packet | ID | Direction | Fields |
|--------|-----|-----------|--------|
| ConnectAccept | - | S→C | `passwordChallenge: byte[]` |
| ServerAuthToken | - | S→C | `serverAccessToken: String, passwordChallenge: byte[]` |
| PasswordResponse | 15 | C→S | `hash: byte[]` |
| PasswordAccepted | - | S→C | (empty) |
| PasswordRejected | - | S→C | `newChallenge: byte[], attemptsRemaining: int` |

## Security Considerations

1. **Password Storage**: Use bcrypt or argon2 for hashing stored passwords
2. **Challenge-Response**: Current protocol uses SHA256(challenge + password) - secure over network
3. **Rate Limiting**: Limit password verification attempts per UUID (e.g., 5 per minute)
4. **Brute Force Protection**: Lock account after X failed attempts
5. **HTTPS**: All API calls must use HTTPS
6. **Server Authentication**: Verify the game server making the API call is legitimate

## Config Options (Future)

```json
{
  "playerPasswords": {
    "enabled": true,
    "required": false,       // If true, players MUST set password
    "minLength": 6,
    "maxAttempts": 3,
    "lockoutMinutes": 5
  }
}
```

## Testing

1. Set password via API/launcher
2. Connect to server - should prompt for password
3. Enter wrong password - should reject with attempts remaining
4. Enter correct password - should connect
5. Remove password - should connect without prompt
6. Test rate limiting
7. Test account lockout

## Notes

- This feature only applies to insecure/F2P mode
- Authenticated mode (JWT) already has secure identity verification
- Password is per-player, not per-server
- Player can use same password on all F2P servers
