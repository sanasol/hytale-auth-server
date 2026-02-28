# Hytale Auth Server

## Technology Stack
- **Runtime**: Node.js 20 with Express
- **JWT**: Ed25519 signing (EdDSA)
- **Database**: Kvrocks (Redis-compatible) for session storage
- **3D Rendering**: Three.js for avatar viewer
- **Testing**: Jest with 81.5% coverage

## Directory Structure

```
hytale-auth-server/
├── src/
│   ├── app.js                    # Main entry point
│   ├── config/index.js           # Configuration management
│   ├── middleware/index.js       # Request logging, CORS
│   ├── routes/
│   │   ├── account.js            # Account management
│   │   ├── admin.js              # Admin dashboard & API
│   │   ├── assets.js             # Static asset serving
│   │   ├── avatar.js             # Avatar rendering & customization
│   │   ├── health.js             # Health check endpoints
│   │   ├── player.js             # Player password & username endpoints
│   │   └── session.js            # Session management (requireAuth guard)
│   ├── services/
│   │   ├── assets.js             # Assets.zip extraction, cosmetics
│   │   ├── auth.js               # JWT authentication + Ed25519 verification
│   │   ├── password.js           # Password hashing, rate limiting, username reservation
│   │   ├── redis.js              # Redis/Kvrocks connection
│   │   └── storage.js            # Data persistence layer
│   └── utils/response.js         # HTTP response helpers
├── dualauth-agent/               # DualAuth ByteBuddy Agent (runtime patching)
├── patcher/                      # Legacy DualAuthPatcher (deprecated)
├── tests/                        # Jest test suites (216 tests)
├── assets/                       # avatar.js, customizer.html
├── legacy/                       # Old server.js (gitignored)
└── Dockerfile                    # Uses src/app.js as entry
```

## Key Features

1. **JWT Authentication** - Ed25519 key pairs in `data/jwt_keys.json`, JWKS at `/.well-known/jwks.json`, mTLS cert binding
2. **Player Password Protection** - Opt-in bcrypt password per UUID, `requireAuth()` on all token endpoints, username reservation, name lock, rate limiting. See `docs/PLAYER_PASSWORD_FEATURE.md`
3. **Avatar System** - 3D viewer at `/avatar/{uuid}`, customizer at `/customizer/{uuid}`, head embed at `/avatar/{uuid}/head?bg=black`
4. **Admin Dashboard** - Protected at `/admin`, paginated servers, pre-render status, password management
5. **Head Pre-render Worker** - Separate Docker container, Puppeteer + SwiftShader, batch processing
6. **Session Tracking** - Redis keys: `session:{token}`, `authgrant:{grant}`, `server:{audience}`, `player:{uuid}`, 10h TTL
7. **CDN Download Redirects** - `/download/{filename}` redirects to configurable CDN URLs, admin settings at `/admin/page/settings`

## Key Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/.well-known/jwks.json` | GET | JWT public key for verification |
| `/game-session/new` | POST | Create new session |
| `/game-session/refresh` | POST | Refresh existing session |
| `/game-session` | DELETE | Player disconnect notification |
| `/server-join/auth-grant` | POST | Authorization grant for server connection |
| `/server-join/auth-token` | POST | Token exchange with cert binding |
| `/my-account/game-profile` | GET/POST | Get/update user profile |
| `/my-account/cosmetics` | GET | Get unlocked cosmetics |
| `/my-account/skin` | POST | Save skin preferences |
| `/profile/uuid/{uuid}` | GET | Lookup by UUID |
| `/profile/username/{username}` | GET | Lookup by username (server-scoped) |
| `/download/{filename}` | GET | Redirect to CDN for file download |
| `/admin/page/settings` | GET | Admin settings page (CDN links, download stats) |
| `/admin/api/settings/downloads` | GET/POST | Get/save CDN download links |
| `/admin/api/settings/download-stats` | GET | Download statistics |
| `/admin/api/settings/download-history` | GET | Download history for charts |
| `/avatar/{uuid}` | GET | 3D avatar viewer page |
| `/avatar/{uuid}/head` | GET | Cached head image (PNG) |
| `/avatar/{uuid}/model` | GET | Avatar model data API |
| `/customizer/{uuid}` | GET | Avatar customizer UI |
| `/admin` | GET | Admin dashboard HTML |
| `/admin/api/stats` | GET | Server statistics |
| `/admin/api/servers` | GET | Paginated server list |
| `/admin/api/prerender` | POST | Trigger head pre-rendering |
| `/health` | GET | Basic health check |
| `/health/detailed` | GET | Status with Redis connection |
| `/player/password/status/{uuid}` | GET | Check if UUID has password protection |
| `/player/password/set` | POST | Set or change password (Bearer + body) |
| `/player/password/remove` | POST | Remove password (Bearer + body) |
| `/player/username/status/{username}` | GET | Check if username is reserved |
| `/api/check-identity` | GET | Check if UUID/username is protected (for DualAuth agent) |
| `/admin/api/players/{uuid}/password-status` | GET | Admin: password status + attempt count |
| `/admin/api/players/{uuid}/password` | DELETE | Admin: remove password + clear lockout |
| `/server/auto-auth` | POST | Auto-generate server tokens (F2P mode) |
| `/oauth2/device/auth` | POST | OAuth device flow - get device code |
| `/oauth2/device/verify` | GET | OAuth device flow - user verification page |
| `/oauth2/token` | POST | OAuth token exchange (device code, refresh) |

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `DOMAIN` | `sanasol.ws` | Auth domain (4-16 chars) |
| `PORT` | `3000` | Server port (443 for HTTPS) |
| `USE_TLS` | `false` | Enable HTTPS with Let's Encrypt |
| `DATA_DIR` | `/app/data` | Persistent data directory |
| `ASSETS_PATH` | `/app/assets/Assets.zip` | Path to game assets |
| `REDIS_URL` | `redis://kvrocks:6666` | Kvrocks connection |
| `ADMIN_PASSWORD` | `changeme` | Admin dashboard password |

## Redis Key Schema

- `metrics:counters` - Hash of all counter values
- `metrics:gauges` - Hash of all gauge values
- `metrics:histogram:{name}` - Hash of histogram bucket counts
- `metrics:avgstate:{name}` - Hash with `sum` and `count` for running averages
- `metrics:labeled:{name}` - Hash of labeled metric values
- `players:with_hardware` - Set of UUIDs that sent hardware telemetry
- `active:players` - Sorted set (score = expiry timestamp)
- `active:servers` - Sorted set of active server UUIDs
- `devicecode:{code}` - Device code data for OAuth flow (TTL: 15 min)
- `settings:global` - Global settings (CDN links)
- `metrics:downloads` - Download counters per URL
- `metrics:downloads:history:{filename}:{urlHash}` - Download history
- `playerpassword:{uuid}` - Bcrypt password hash (no TTL)
- `pwattempts:{uuid}` - Failed password attempt counter (TTL: 900s)
- `pwset_ratelimit:{ip}` - IP rate limit for password-set (TTL: 3600s)
- `username_reserved:{name}` - Username reservation JSON `{ uuid, username, ip, reservedAt }`
- `uuid_username:{uuid}` - UUID → reserved username mapping
- `username_audit` - Audit log list (last 1000 entries)

## Services Architecture

**Assets Service** (`src/services/assets.js`):
- `assetsExist()`, `loadCosmeticsFromAssets()`, `loadCosmeticConfigs()`, `loadGradientSets()`
- `extractAsset(path)` - Extract single asset using `unzip -p` (no disk I/O)
- `resolveSkinPart(category, value)` - Resolve part ID to model/texture paths

**Storage Service** (`src/services/storage.js`):
- Session: `registerSession()`, `removeSession()`, `getSession()`
- Auth grants: `registerAuthGrant()`, `consumeAuthGrant()`
- User data: `getUserData()`, `saveUserData()`
- Admin: `getKeyCounts()`, `getPaginatedServers()`, `getAllPlayerUuids()`

## Cosmetic System

**Categories**: bodyCharacteristic, cape, earAccessory, ears, eyebrows, eyes, face, faceAccessory, facialHair, gloves, haircut, headAccessory, mouth, overpants, overtop, pants, shoes, skinFeature, undertop, underwear

**Skin data format**: `ItemId.ColorId.VariantId` (variant optional, color can be empty: `ItemId..VariantId`)

**Cape variants**: `Neck_Piece` (with shoulder collar) vs `NoNeck` (cape only). Non-greyscale capes use `variant.Textures[colorId]` directly. Greyscale capes use `variant.GreyscaleTexture` + `GradientSet` for tinting.

**Node attachment**: Cosmetic nodes attach to player bones by name. Attached nodes: only apply orientation (bone provides position). Non-attached: apply full transform. Shape.offset is rotated by node's quaternion.

## Avatar Response Structure

```javascript
{
  uuid: string,
  skinTone: string,      // e.g., "SkinTone_01" or "01"
  bodyType: string,      // e.g., "Default" or "Muscular"
  parts: {
    haircut: string,     // e.g., "Haircut_ShortMessy.Blue"
    eyes: string, eyebrows: string, face: string, // ...
  },
  raw: object
}
```

## Asset Format

- `.blockymodel` - 3D model definitions with bone hierarchy (JSON-based)
- `.blockyanim` - Animation keyframe data
- PNG textures - Often greyscale with gradient-based coloring
- Configuration in `Assets/CharacterCreator/*.json`
- Skin tones numbered 01-30 (with leading zero), body types: Default, Slim, Muscular

### Head Rendering
- Camera: `(0.00, 1.10, -1.00)`, LookAt Y: `1.00`, FOV: `40`, character offset `y: -1.55`
- Renders 37 meshes (Head, Eyelids, Hair, Eyes, Eyebrows, Face, Beard, Ears, Mouth, Accessories)
- Background: `transparent`, `white`, `black`, or hex color

### Eye Rendering
- Composite: `R-Eye-Background` + `R-Eye` (pupil) meshes
- Eyelid meshes: `L-Eyelid`, `R-Eyelid`, `L-Eyelid-Bot`, `R-Eyelid-Bot`
- Shadow: top corners darker, gradient to transparent at bottom

## Running Tests

```bash
npm test                              # Run all tests
npm test -- --coverage                # With coverage report
npm test -- tests/unit/services/assets.test.js  # Specific file
```

## GitHub Repository
https://github.com/sanasol/hytale-auth-server

## Player Password Protection
Per-player password authentication is implemented. Full API reference and launcher integration guide: `docs/PLAYER_PASSWORD_FEATURE.md`
