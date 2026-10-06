# Social backend deployment and checks — 2026-10-06

## Implemented

The existing auth process now owns signed-session social routes, per-user Redis
state, atomic symmetric relationship updates, indexed name lookup, privacy-aware
presence, favorites/blocks, party/world invitations and temporary TURN credentials.
Social state and events commit together with WATCH/MULTI. The gateway consumes the
same database pubsub and relays ICE only between authorized participants. Coturn
is deployed separately; it does not replace the QUIC game server.

Privacy numbers recovered: 0 Everyone, 1 Friends, 2 Nobody. Presence TTL300s is
our policy: real clients heartbeat about every120s. Expiry emits offline updates.
No invite code is returned in public presence projections. Joining and accepting
an invitation enforce identity, expiry, friendship/grants, blocks and privacy.

A reverse username index was populated for 502978 existing names; name writers
maintain it atomically. Duplicate names return409. Run
`node scripts/index-social-usernames.js` after deploying the writer on a new database.

The actual release client's discovery parser also exposed two existing null-field
bugs: missing upstream dates now use the Unix epoch sentinel; unknown owner IDs
use Guid.Empty. These represent unavailable metadata, not invented owners/dates.

## Executed checks

- Disposable Redis integration: two identities, concurrent duplicate accept,
  friendship, privacy, explicit offline, host join, party invite/accept/leader leave,
  world invite acceptance, profile skin string, favorites, expiry, blocks, foreign
  invite refusal and bad signatures. Run with explicit disposable REDIS_URL and
  DATA_DIR: `node --test tests/integration/social.test.js`.
- Gateway Node tests: valid/invalid signatures, two-peer opaque relay, third-user
  denial, notifications, malformed messages, disconnect cleanup and reconnect replay.
- Public HTTPS/WSS smoke: signed sessions, friend requests/acceptance, presence,
  real peer.session.open/send and TURN credentials.
- Real release0.6.8 clients: WsTestA1006 launched via F2P Evo; WsTestB1006 launched
  with the same launcher arguments in an isolated app copy because LaunchServices
  otherwise reused the running game instance. Separate save directories were used.
- Request and acceptance were performed in game UI. Both players appeared online.
  B selected A's Join World; WS exchanged peer messages, ICE nominated a direct
  path, mutual authentication succeeded, server added B, and client reached InGame.
  A received B's game chat message.
- B was relaunched with local-only HYTALE_LIVECONFIG_URL and ice_force_relay=true.
  At18:03:14 client logged `ICE selected Relayed` and `ICE connecting over a Relayed
  path ... via relay 208.69.78.130:3478`; at18:03:21 it reached OnWorldJoined.
  A received B's `TURN relay test 1006` chat. No production feature flag changed.
- The server browser rendered featured entries and server listings after the null-field fixes.
- Two independent aioice connections also exchanged packets using only relay candidates.
- Graceful game exit removed B from the host world. Gateway restart reconnected
  clients and replayed friendships; the native handler logged duplicate accepted
  events as ignored, without duplicating list entries.

Local evidence logs remain in Downloads/HytaleWsTest-A/Logs and
/tmp/HytaleWsTest-B/Logs. Do not publish full logs: they can contain session material,
addresses and invite codes. Test launcher scripts/credentials remain outside Git. Both clients were closed normally;
the launcher selection and save directory were restored to Sanasol[F2P] and
Downloads/Test1. The temporary local feature-flag server and test Redis were stopped.

## Limits and remaining coverage

Both real clients ran on the same Mac/network, including the forced-relay test.
Different-ISP and prolonged relay/load behavior remain untested. Party/world-invite
APIs are covered, but a complete two-client party UI matrix was not exercised.
Discord account mapping (`/friends/resolve-discord`) is explicitly501.

The gateway is a single instance with ephemeral sessions. Reconnect replays current
friends/requests and the latest relationship changes for1000 peers; older removals
need a fresh HTTP snapshot/client restart. There is no generic acknowledged durable
notification stream. Official party leader policy is unknown; this implementation
transfers leadership to the first remaining member.

Pre-existing client warnings about accessory attachment targets, news image host
allowlist and local server telemetry/live-config403 are outside the social/ICE fix.
They did not prevent either direct or forced-relay game join.

## Operations

Auth runtime: /var/www/traefik/hytale-auth; gateway/turn: /var/www/traefik/socket-gateway.
The infra repository's gateway README contains ports, limits, deployment and rollback.
TURN_SECRET_FILE points at the mounted auth-data/turn-secret, which matches coturn's
private static-auth-secret. Never commit that file, credentials or test JWTs.
