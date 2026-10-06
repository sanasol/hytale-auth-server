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
  A received B's `TURN relay test 1006` chat. The relay override was local only.
- The server browser rendered featured entries and server listings after the null-field fixes.
- Two independent aioice connections also exchanged packets using only relay candidates.
- Graceful game exit removed B from the host world. Gateway restart reconnected
  clients and replayed friendships; the native handler logged duplicate accepted
  events as ignored, without duplicating list entries.

Local evidence logs remain in Downloads/HytaleWsTest-A/Logs and
/tmp/HytaleWsTest-B/Logs. Do not publish full logs: they can contain session material,
addresses and invite codes. Test launcher scripts/credentials remain outside Git.
B closed normally. A stopped responding to quit actions during cleanup despite
logging SDL_QUIT; its local Java server received SIGTERM and logged config save
and Shutdown completed before the stuck client process was killed. This native
exit issue is not diagnosed. No production game server was stopped;
the launcher selection and save directory were restored to Sanasol[F2P] and
Downloads/Test1. The temporary local feature-flag server and test Redis were stopped.

## Limits and remaining coverage

Both real clients ran on the same Mac/network, including the forced-relay test.
Different-ISP and prolonged relay/load behavior remain untested. Party UI creation, invitation, acceptance, rejection, member leave, leader leave
and leadership transfer were exercised in two real clients. Pending invitation
survived a Kvrocks restart and was then accepted successfully. The party also
survived a client restart and gateway reconnect. Capacity, cancellation, expiry
and unauthorized invitations are covered by the disposable database test.
Discord account mapping (`/friends/resolve-discord`) is explicitly501.

The gateway is a single instance with ephemeral sessions. Reconnect replays current
friends/requests and the latest relationship changes for1000 peers; older removals
need a fresh HTTP snapshot/client restart. There is no generic acknowledged durable
notification stream. Native UI allows invitations only from the leader; the backend enforces the same
rule. Leadership transfers to the first remaining member; the native UI updates
its crown and allows the successor to invite.

Pre-existing client warnings about accessory attachment targets, news image host
allowlist and local server telemetry/live-config403 are outside the social/ICE fix.
They did not prevent either direct or forced-relay game join.

## Operations

Auth runtime: /var/www/traefik/hytale-auth; gateway/turn: /var/www/traefik/socket-gateway.
The infra repository's gateway README contains ports, limits, deployment and rollback.
TURN_SECRET_FILE points at the mounted auth-data/turn-secret, which matches coturn's
private static-auth-secret. Never commit that file, credentials or test JWTs.

## Party UI and recovery follow-up

Release 0.6.8 hides party controls unless `enable_parties` is true. This flag is
now enabled in production live config, verified by launching B without a local
config override. Native registry at 0x14093e840 and sidebar 0x140209950 establish
the flag gate. Existing HTTP endpoints alone did not expose the UI.

The UI acceptance test exposed a contract error: `/party/invites/accept` must
return PartyInvite, not PartyInfo. Native wrappers at 0x14095fa80/0x14095f970 use
the same response type, requiring inviteUuid, inviterUuid, invitedPlayerUuid,
partyId, expiresAt and createdAt. The successful callback then fetches the party
and pending invitations. Returning PartyInfo committed membership but broke
recipient deserialization and left its UI stale. The corrected response was
verified in both clients, including accepting an invitation after DB restart.

A gateway reconnect was observed fetching party, incoming/sent party invitations,
incoming/sent world invitations and friends. Closing sockets with 1013 when
Redis/PubSub disconnects forces this native resynchronization. Gateway readiness
now requires an acknowledged subscription. A real Redis restart is a runnable
regression in the gateway test. Social WATCH transactions use a dedicated
connection with reconnect/replay disabled: an ambiguous EXEC failure returns503,
never an automatic second mutation. The integration test kills the WATCH TCP
connection and checks that no unguarded write occurs.

Production Kvrocks now enables WAL sync before acknowledging writes. Nine social
records and both test users' stable state survived container recreation. Daily
checkpoint archives retain seven days; a checkpoint was restored in an isolated
Kvrocks container and its social hashes matched. See the infra repository's
socket-gateway/BACKUPS.txt for the tested procedure. Copies remain on the same VPS;
an independent backup destination is still needed for disk/host loss.

A small post-change sample of 100 sequential database SETs measured median1.76ms,
p955.60ms. This is not a system load benchmark or an availability guarantee.

The world-invite UI test found another response mismatch: WorldInviteResponse
requires a non-nullable serverUuid (native serializer 0x1409a0582 → Guid writer
0x1410cf370). The send request contains only targetUuid and inviteCode, so a local
host can legitimately have no server UUID in presence. World send/list and join
responses now use Guid.Empty for that absent value, including invitations already
stored with null. The integration test covers both new and legacy invitations.
After the fix, B rejected an invitation through the UI, then accepted a fresh one
at18:46:22 and reached OnWorldJoined at18:46:30. A saw B join and received its
chat message. Expired invitations cannot be accepted, but their rightful owner
can reject/cancel them to clear stale native notifications; foreign actions
still return404. This expiry cleanup has a runnable integration regression.

A separate sample from the test Mac to production HTTPS /friends completed20
requests: median75ms, p95165ms, max191ms. It verifies ordinary response latency for
the test account, not concurrent load capacity.
