# Implementing the Hytale 0.6.8 backend

Investigation and implementation: 2026-10-06. The recovered client contract below now backs a deployed social API, WebSocket signaling gateway and coturn relay. See [deployment and runtime checks](backend-social-deployment.md) for verified behavior and remaining limits.

The matching Windows release client has SHA-256 `05f476a25b967005b5480c46152c57811c0bc62aacfabbbff5a98d5e66043ba5`. Its distribution's paired server manifest identifies release0.6.8/revision `d2feeb3997f2efc9b4fe23282a3ece38f0618047`, matching the installed Mac game's version. Windows addresses below do not apply to the Mac binary. The Mac inventory independently corroborates endpoint/event spellings.

Evidence: [social contract](backend-release-social.txt), [WebSocket lifecycle](backend-release-gateway.txt), [ICE payload](backend-release-ice.txt), [TURN](backend-release-turn.txt), [batch profiles](backend-release-profile.txt). These ledgers include function addresses and limitations. Full local ASM/pseudocode and binary identities are under workspace `.temp/rea-hytale`; saved Ghidra projects are under `research/ghidra-project`.

## What has to work together

```mermaid
sequenceDiagram
  participant A as Client A
  participant API as HTTP backend and state
  participant WS as Socket gateway
  participant B as Client B / host
  participant TURN as TURN relay
  A->>WS: /ws + session Bearer
  WS-->>A: gateway.connected(connection_id)
  B->>WS: /ws + session Bearer
  WS-->>B: gateway.connected(connection_id)
  A->>API: POST /friend-requests/by-username
  API-->>WS: Redis social:v1:events
  WS-->>B: gateway.notification(friend.request.received)
  B->>API: POST /friend-requests/accept
  WS-->>A: gateway.notification(friend.request.accepted)
  B->>API: POST /presence/heartbeat + world/join metadata
  A->>API: POST /presence/join-world or accept world invite
  API-->>A: Join response / inviteCode
  A->>WS: peer.session.open(peer_uuid,kind=ice,client_ref)
  WS-->>A: peer.session.ack(session_id,peer_online,client_ref)
  WS-->>B: gateway.notification(peer.session.opened)
  A->>WS: peer.send(payload={v:1,type:offer,...})
  WS-->>B: gateway.notification(peer.message)
  B->>WS: peer.send(payload={v:1,type:answer,...})
  WS-->>A: gateway.notification(peer.message)
  A->>API: POST /turn-credentials when enabled
  API-->>A: Real relay URLs and temporary credentials
  A->>TURN: TURN allocation when relay is needed
  Note over A,B: ICE connectivity + owner ConfigureIcePeer; QUIC and mutual auth follow
```

Sequence is a reconstruction of observed interface roles, not a captured end-to-end trace. It does not assert ordering of an ack versus remote notification, or official internal event-bus topology.

## HTTP social contract

All methods below are traced through native async dispatch to the HTTP method pool, not guessed from endpoint names. Read the linked ledger before treating a field as required or assigning enum values.

| Function | Method and path | Request fields / returned data |
|---|---|---|
| List friends | GET `/friends` | Response `friends` with player/social metadata |
| Incoming/outgoing requests | GET `/friend-requests/incoming`, `/friend-requests/outgoing` | Response `requests` |
| Request by UUID | POST `/friend-requests` | `targetUuid` |
| Request by name | POST `/friend-requests/by-username` | `targetUsername` (serializer slot traced directly) |
| Accept/reject | POST `/friend-requests/accept`, `/friend-requests/reject` | `requesterUuid` |
| Remove friend | POST `/friends/remove` | `friendUuid` |
| List blocks | GET `/blocks` | `blocks` |
| Block/unblock | POST `/blocks`, `/blocks/remove` | `targetUuid` / `blockedUuid` |
| Favorites | GET / PUT `/friends/favorites` | `favorites` |
| Resolve Discord IDs | POST `/friends/resolve-discord` | Encoded keys `discord_ids`, `mappings`; exact mapping-entry semantics still open |
| Friends' presence | GET `/presence/friends` | `friends` |
| Privacy settings | GET / PUT `/presence/settings` | See below |
| Appear offline | PUT `/presence/appear-offline` | `appearOffline` |
| Presence heartbeat | POST `/presence/heartbeat` | Rich heartbeat below |
| Clear presence | POST `/presence/clear` | Separate no-response generic path; body details not inferred |
| Join a friend's world | POST `/presence/join-world` | `targetUuid`; join-response fields below |
| Current party | GET `/party` | Party ID, leader, members, size information |
| Create party | POST `/party/create` | `maxSize` |
| Leave party | POST `/party/leave` | Body details not inferred |
| Party invites | GET `/party/invites`, `/party/invites/sent` | `invites` |
| Send party invite | POST `/party/invites/send` | `targetUuid`, `expiresInSeconds` |
| Accept/reject party invite | POST `/party/invites/accept`, `/party/invites/reject` | `inviteUuid` |
| World invites | GET `/world-invites`, `/world-invites/sent` | `invites` |
| Send world invite | POST `/world-invites` | `targetUuid`, `inviteCode` |
| Accept/reject/cancel world invite | POST `/world-invites/accept`, `/world-invites/reject`, `/world-invites/cancel` | `inviteUuid` |

Camel-case REST keys come from encoded serializer contexts and associated CLR-property sets; only explicitly documented per-field slot traces are individually proven. They must not be replaced with the snake-case aliases of the WebSocket payloads.

Friends expose `uuid`, `since`, `isOnline`, `discordUserId`, `isFavorite`, `serverName`, `worldName`, `gameMode`, `canJoin`, `isWorldOwner`. A request entry exposes `requesterUuid`, `targetUuid`, `createdAt`. These lists are field inventories, not declarations that every property is required or non-null.

Party information includes `partyId`, `leaderUuid`, `members`, `maxSize`, `currentSize`. Invitation information includes IDs, inviter/invited player, party or world/server metadata, and creation/expiry dates. Some CLR `IsExpired` values may be computed and absent from the serializer context; do not blindly expose all CLR properties as wire fields.

The world-join result has `serverUuid`, `host`, `port`, `serverName`, `worldName`, `inviteCode`. Full invite-code encoding is not recovered here. Preserve host-supplied opaque codes rather than inventing a replacement format.

## Batch profile prerequisite

Matching release code confirms `POST /profile/uuids`, request `{uuids:[...]}`, response:

```json
{"profiles":[{"uuid":"<UUID>","username":"<name>","skin":"<serialized skin>"}]}
```

The response serializer directly calls the list serializer and its element serializer. `profiles`, `uuid` and `username` carry required-property flags; `skin` is optional and is a JSON string or null, **not a nested object**. No `entitlements` field belongs to this element. Unknown-UUID and partial-result policies are not recovered. Returning the current generic profile/token fallback is incompatible with the required wrapper.

## Presence and privacy

Observed heartbeat fields:

```text
status, activity, serverUuid, serverName, worldName,
gameMode, serverHost, serverPort, inviteCode
```

- `status` and `activity` serialize as strings. Client construction emits lowercase `online`; activity is `playing` or `menus`.
- The inspected consumers treat exact lowercase `offline` or an empty status as offline. A capitalized or numeric replacement is not equivalent.
- `showOnline`, `showLocation`, `showActivity`, `allowInvites` serialize as numbers. Recovered enum: 0 = Everyone, 1 = Friends, 2 = Nobody; defaults are 0. See backend-release-social-implementation.txt.
- `allowJoin` is boolean; `allowFriendRequests` is optional boolean, omitted when it has no value.
- `since` and notification `created_at` use quoted JSON dates, not numeric epochs. Full accepted date ranges/format variants remain untested.

The implemented backend persists rich heartbeat fields and emits friend presence events. Never expose private invite codes/host addresses merely because the client sends them: apply the selected visibility/join policy to the requesting identity.

Implemented state: latest heartbeat + timestamp + privacy settings + explicit offline override, keyed by authenticated UUID. Presence expires after 300 seconds; the real client sends heartbeats about every 120 seconds. This is our policy, not a recovered official TTL. Socket disconnect does not by itself prove the game stopped; multiple sessions and reconnect must be considered.

## WebSocket rules that affect compatibility

1. Connect `/ws` with `Authorization: Bearer <sessionToken>`. Bind the verified identity to this connection. The existing stub verifies the signature but has no recipient routing.
2. Return `gateway.connected` with `data.connection_id`. The client stores this ID. No REST header/body linkage to this ID was established; do not invent a mandatory connection-ID HTTP header.
3. Top-level dispatch recognizes `gateway.connected`, `gateway.close`, `gateway.notification`, `peer.session.ack`, `peer.error`. Social events belong **inside** `gateway.notification`, not as top-level messages.
4. Notification fields are `id`, `type`, `timestamp`, `data`. The exact representation and replay meaning of `id` are still open.
5. On `peer.session.open`, preserve `client_ref` exactly. Client generates it as a GUID string and waits for that pending key. Missing/wrong `client_ref` makes ack/error ineffective.
6. Ack fields: `session_id`, boolean `peer_online`, `client_ref`. Even `peer_online:false` is accepted by the inspected ack handler; behavior farther downstream still needs checking.
7. Notify the recipient with `peer.session.opened` data `session_id`, `from_uuid`, `kind`. `from_uuid` must parse as a UUID. Derive it from the authenticated sender, never a caller-supplied identity.
8. Forward `peer.send` as notification `peer.message`, carrying the established `session_id`, real sender UUID, and unchanged application `payload`.
9. Do not demand a monotonically increasing client `seq`: the inspected sender does not increment a sequence counter, and the inspected ingress handler does not inspect `seq`. Exact null/omission behavior still needs serializer verification.
10. `peer.session.closed` removes local session state and invokes closure once. Close operations should be idempotent in our implementation.
11. `peer.error` faults a pending open by `client_ref`. Sending an error for an established session by `session_id` alone is not handled by that particular error function; use the recovered closure path for established-session termination.

Reconnect is client-managed with jitter: [5,10), [15,30), [30,60), then [60,120) seconds. This is not a server heartbeat requirement. No fixed application ping message was found in the top-level dispatcher; WebSocket control ping/pong is distinct.

Proposed minimal state: verified connection index by user/session, pending/open peer sessions with exactly two authorized endpoints, and lifecycle cleanup. Persist social state independently. With the existing separate gateway, an explicit state/event connection to auth is needed; HTTP handlers and the WS stub currently share none. In one process, direct post-commit event delivery is sufficient initially; cross-process deployment needs a concrete supported transport before it can work.

Unknown official policies: whether friendship is required for every peer kind, offline queueing, multiple-device fanout, replay, rate limits and exact gateway error codes. Define conservative server policies and label them ours. Do not interpret their absence from client code as permission to forward arbitrary traffic.

## ICE payload: relay unchanged

Confirmed in matching0.6.8:

```text
peer.session.open.kind = "ice"
payload = {v, type, session, ufrag, pwd, candidates, reason}
v = 1
type = "offer" | "answer" | "candidate" | "end"
candidate = {type, address, port, priority, foundation}
```

The inner `session` is a separately supplied ICE context. Do not overwrite it with the outer gateway `session_id`. Receiver checks version1 and context equality. Host handles a valid offer with an answer and accepts additional candidates; malformed credentials lead to end/bad credentials, and too many offers lead to end/too many offers. These are **inner ICE reason strings**, not proven gateway `peer.error.code` values.

Gateway relays this JSON; it does not generate ICE passwords, answer STUN or emulate the game server. Host client configures its actual local server with owner-only `ConfigureIcePeer`; NAT probing and subsequent QUIC happen below the social backend.

## TURN is a separate runtime dependency

Current client performs:

```http
POST https://sessions.<configured-domain>/turn-credentials
Authorization: Bearer <sessionToken>
Accept: application/json
```

Response field inventory:

```text
{iceServers:[{urls:[...], username, credential}], expiresAt}
```

There is no `ttl`/`ttlSeconds` field in this response DTO. `expiresAt` is nullable date-like data; use an actual expiry tied to issued credentials and verify the client's accepted serialization before shipping. `stun:` and `turn:` URLs were observed; other transport variants were not established.

Compiled defaults: `enable_ice_framework=true`, `enable_turn_credentials=true`, `ice_force_relay=false`. Liveconfig can override them. `ice_servers_override` supports explicit STUN/TURN entries for testing. Credential failure logs that it is continuing without relay.

For relay-dependent NAT combinations, JSON-only credentials or a WS forwarder cannot work: deploy a real reachable TURN service and mint valid temporary credentials for it. Test forced relay and actual packet flow; a successful credentials response is not acceptance evidence. Direct connections can succeed without TURN.

## Implemented state transitions (our policy)

- Friend request: authenticate sender → resolve recipient → enforce block/privacy/duplicate rules → persist pending request → notify recipient. Accept must remove pending and create one symmetric friendship before notifying both views. Reject/remove/block update the same shared state, with corresponding events. Use atomic updates to avoid two inconsistent relationship records.
- Presence: preserve host/world/join data → calculate requester-visible projection → emit `friend.presence.updated` when it changes. Privacy updates must also update that projection.
- Party: membership, leader and pending invites share consistent state. Accept checks expiry, recipient and capacity. Leaving the leader requires a defined transfer/disband policy; official choice is unknown.
- World invite: keep host-supplied code plus sender, recipient and expiry. Accept resolves current reachable host metadata and enforces access; closed or stale worlds must not silently yield success.
- Peer session: establish two verified participants → correlate ack → notify other endpoint → relay only between those participants → close/cleanup. New connection IDs after reconnect must not leave obsolete sessions as reachable.

Do not add a universal successful fallback for unimplemented paths. A false200 prevents clients and tests from distinguishing unsupported operations from successful state changes.

## Acceptance gates for a genuinely working backend

1. Protocol prerequisites: release client parses server listings, batch profiles and errors; no malformed null dates or missing response wrappers.
2. Two distinct test identities: request by username, incoming list and live notification, accept, both friend lists, unfriending, blocks and favorites. Repeat a mutation and test concurrent accepts.
3. Presence: menu vs world, online/offline, privacy changes, explicit offline, clean exit and dropped connection. Confirm joining uses the latest host metadata.
4. Party/world invites: sender and recipient see consistent lists/events; expiry/rejection/cancel and leader departure work.
5. Peer signaling: matching client_ref, authorized routing, payload preservation, unknown/closed sessions, recipient offline and disconnect during handshake.
6. Real game join: two clients on different networks, direct ICE and forced TURN relay, mutual game authentication and actual world interaction. Verify both graceful and abrupt disconnect.
7. Reconnect: reconnect backoff, new connection identity, refreshed lists, stale peer cleanup, expired access token.

The checked subset and remaining gates are recorded in backend-social-deployment.md; the list above is not a claim of complete coverage. Steps1–7 are not completed merely by static research. Runtime behavior and exact server business semantics cannot be proven from serializers alone. The recovered contract is sufficient to start a real friends/presence/WS implementation without inventing endpoint names or mixing protocol layers; remaining ambiguities are explicitly listed in the evidence ledgers.
