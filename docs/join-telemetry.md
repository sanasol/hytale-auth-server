# Native join telemetry: confirmed contract

Evidence is Windows release 0.6.8 (`05f476a25b967005b5480c46152c57811c0bc62aacfabbbff5a98d5e66043ba5`) plus actual macOS 0.6.8 telemetry saved by Test1. This research did not modify or operate either client.

## Transport and identity

Native sender `0x1416653c0` submits JSON with POST to `https://telemetry.<configured domain>/telemetry/client`. POST static field is loaded at `0x14166554d` (+0x18); host at `0x141665568`, path at `0x1416655be`. `Content-Type: application/json` and `Authorization: Bearer <identity token>` are built at `0x1416657de` and `0x1416656fd`. Missing identity logs `No identity token available, skipping telemetry send` at `0x141665c70`. The sender retries failures, so packet delivery is not inherently exactly-once.

Observed envelope is `{type:"event",event_name,event_data,timestamp,sequence,session_id,...}`. Do not require a session-token scope at this endpoint: the client uses an identity token. Existing `telemetry.js` only decodes the token (`parseToken`), not signature verification. Verify using the identity-token contract before treating counts as authenticated; allow legacy telemetry separately if compatibility is necessary.

## Events

`server_connect` carries:

- `time_to_connect_ms`: number
- `success`: boolean
- `is_p2p`: boolean
- `candidate_count`: number
- `inbound_datagrams_seen`: boolean

Schema function `0x1409bad20`, field references `0x1409bae17`, `0x1409baf67`, `0x1409bb0ba`, `0x1409bb1f4`, `0x1409bb32e`. Success path of OnConnected `0x1408e26d0` passes true at `0x1408e2851` to event builder `0x1408e2cd0`; failure handling also emits `server_connect`. This reports connection outcome, not successful world loading. No host, target UUID, social JoinWorld operation ID or direct/relay flag is present in this DTO.

`ice_result` carries:

- `reason`: enum converted to string
- `selected_candidate_type`: nullable enum string
- `local_candidate_types`, `remote_candidate_types`: comma-separated strings, not JSON arrays
- `check_count`: integer
- `elapsed_ms`: number
- `relay_required`, `used_relay`: booleans
- `credential_outcome`: string

Schema `0x1409b26e0`; producer `0x1408e0e00`. Event name at `0x1408e10aa`; candidate sets join with comma at `0x1408e100b` / `0x1408e103d`; relay booleans set at `0x1408e1081` / `0x1408e108c`. `used_relay` is the concrete route outcome indicator. `relay_required` and possession of TURN credentials are not proof that a relay was used. ICE outcome is still not proof that authentication or world loading succeeded. Exact enum literals were not enumerated during this bounded pass; retain raw values rather than invent a success whitelist.

`world_joined` carries `load_time_ms`, `initial_chunk_load_time_ms`, `game_mode`, `is_singleplayer`. Schema `0x1409c38e0`, producer `0x14044fa90`, emission at `0x14044fb11`. Game mode literals include adventure/creative/unknown. This is the stronger client-reported indication of entering a world, but also includes local worlds and potentially world transitions; it is not a dedicated social JoinWorld success response.

`state_transition` has `from_state`, `to_state`, `transition_duration_ms`, optional `connection_type` and `world_type` (schema `0x1409c2af0`). Do not reinterpret connection_type as direct/relay: its value mapping was not established here.

## Runtime corroboration

Files `/Users/sanasol/Downloads/Test1/Telemetry/2026-10-06_18-23-03_8e6116a9.jsonl.gz` and `2026-10-06_18-24-06_03c7b609.jsonl.gz` contain three `server_connect` packets with `success:false`, `is_p2p:false`, `candidate_count:0`, `inbound_datagrams_seen:true`, with times 187, 0, 0 ms. Each is followed by game_loading -> disconnection. This confirms native failure reporting and field spelling on macOS, not a production-wide success rate. Files have a UTF-8 BOM; open with utf-8-sig before parsing JSONL. No ICE runtime packet was found in these three inspected Test1 sessions.

## Current backend gaps and counting rules

The existing handler already understands the native event envelope and persists all event_data, including ice_result. However, `ice_result` has no dedicated aggregation. Durable `recordEvent` retains only the last 1000 packets per type, expires them after seven days, and daily counts retain 90 days and count event names only. It cannot reconstruct annual success/failure or relay rates from old totals. Existing success metrics are process-local counters and packets are not deduplicated.

For honest reporting, keep separate counters for REST JoinWorld requests, client server_connect successes/failures, ICE outcomes by used_relay, and multiplayer world_joined events. Deduplicate authenticated packets by player UUID + session_id + sequence; sender retries can otherwise inflate results. Count zero-millisecond samples as valid. Do not map a missing telemetry packet to a failed connection. A social JoinWorld request, ICE result and world_joined cannot be joined exactly with the shipped DTOs: there is no shared attempt ID. Same session/sequence/time proximity is only an inference, especially across retries, other server connects and world transitions. Explicitly label this limitation rather than presenting exact per-JoinWorld end-to-end success/direct/relay counts.

Assembly evidence: `.temp/rea-hytale/join-telemetry-*.asm` and `join-telemetry-xrefs.txt` in the parent workspace. No production change made in this research pass.

## Additional sequence and local-world proof

`sequence` is a 32-bit signed counter, not Int64. The constructor initializes it to 1 at `0x1409c6c8b`; synchronized getter `0x1409c7470` reads dword `[service+0x68]`, increments it, returns the old value. Event producer stores the result as dword `[packet+0x18]` at `0x140b02948`. The session UUID is generated once by the TelemetryService constructor and formatted as GUID `D` (`0x1409c6cf5..0x1409c6d1b`); it spans multiple connects within that run. `timestamp` uses .NET round-trip `O` at `0x1409c74dc`. Event creation is gated by two service flags at `0x140b028f9` / `0x140b02906`; the exact settings controlling these flags were not traced. Therefore absent events cannot be assumed to mean failure.

Actual HytaleWsTest-A telemetry at 2026-10-06T15:44:13 and 16:34:57 contains `server_connect success:true,is_p2p:false`, followed by `world_joined is_singleplayer:true`. Thus **server_connect success also includes local-server connections**. Exclude `world_joined is_singleplayer:true` from multiplayer-world counts; do not describe all server_connect successes as multiplayer joins. No ice_result packet was found in the inspected HytaleWsTest-A/B or Test1 telemetry files.

Native metadata contains IceOutcomeReason-associated names `Nominated`, `AllPairsFailed`, `NoCandidates`, `SignalingUnavailable` near file offset 0x1ca057d. This does not establish the entire enum or numeric mapping: additional shared names may live elsewhere. A complete success/failure whitelist is not claimed. The `relay_required` getter `0x1402d5ee0` tests reason numeric 1 and check_count > 0; `used_relay` getter `0x1402d5f00` tests a present selected-candidate enum equal to numeric 0. Preserve the native booleans rather than inferring them from reason names.

## Deployed verification, 2026-10-06

The research descriptions above refer to the pre-change implementation. The
current route verifies signed identity tokens and counts through telemetryMetrics.
Replayed the isolated test client's actual local-world server_connect and
world_joined packets from its 18:33 session against
`https://telemetry.sanasol.ws/telemetry/client`: first submissions returned200;
identical retries returned200 with `counted:false`. Local-world entry is excluded
from multiplayer-world counts. These are replayed genuine client packets, not a
new multiplayer end-to-end test after deployment. Direct/relay classification
also uses actual saved testB ice_result records with `reason:Nominated` and
`used_relay:false/true`; the earlier negative search was limited to its then
inspected files.
