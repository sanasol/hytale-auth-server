# Social usage metrics

The Servers admin panel reads `/admin/api/social-usage?days=30` (route in `src/app.js`). `socialMetrics.read()` reads one totals hash and 1–365 explicitly named daily hashes, without enumerating the database. Daily UTC hashes expire 365 days after their day's start; cumulative counters and baseline offsets persist.

## Measurement semantics

| Metric | Meaning |
| --- | --- |
| current.friendEdges | Unique stored friendship pairs; each mutual pair counts once. |
| current.parties | Stored nonempty parties; not necessarily online/playing. |
| friendsAccepted/Removed | Committed friendship changes; blocking a current friend also removes a friendship. |
| partiesCreated/Closed | Creation and removal of the last member, respectively. |
| partyMembersJoined/Left | Invite-driven joins and explicit leaves; creation does not additionally count its founder as a join. |
| partyInvites*, worldInvites* | Committed Sent/Accepted/Rejected/Canceled actions. Passive expiry is not rejection; explicitly dismissing an expired stored invite counts. Acceptance does not prove gameplay entry. |
| serverFavorites*, serverLikes* | Actual per-user set additions/removals, excluding no-op repeats. |
| worldJoinHttpSuccess/Denied/Errors | POST `/presence/join-world` authorization outcomes. Denied includes 401/4xx; errors cover server failures. Repeated HTTP requests count separately. |
| clientConnectSuccess/Failure | Authenticated client-reported server_connect, including local connections. |
| clientP2PConnectSuccess/Failure | The same event restricted to is_p2p=true. |
| clientWorldJoined | Client-reported multiplayer world_joined; not correlated to a particular invitation/friend. |
| iceDirect/Relay | Endpoint reports of ICE nomination, classified by used_relay. Host and joining client can both report. Nomination does not itself prove world entry. |
| iceFailed/Unknown | Recognized terminal ICE failures or unclassified outcomes. |

Friend request and block counters also represent committed changes. Client telemetry is self-reported despite authenticated attribution. ICE totals and world-entry totals measure different populations; their ratio is not a direct/relay connection success rate.

`since` is first-event server ingestion time. Backend action days use server time; accepted delayed telemetry retains its client event day. Dates before tracking began are not reconstructed history: zero-filled rows mean no retained count, not verified historical inactivity. Cumulative totals can exceed the selected period or retained daily history.

## Atomicity

`social.change` supplies an optional third callback argument, `metric(name, delta=1)`. Existing two-argument callbacks remain compatible. Every WATCH retry builds fresh increments. State, notifications, and metric increments commit in one MULTI/EXEC, so a conflict or callback failure does not count an operation. Lost transaction connections are not automatically replayed.

Standalone HTTP outcome counters are best effort: metric storage failures are logged and do not turn otherwise successful authorization into failure. They can undercount during an outage. Telemetry dedup markers and counters commit together; dedup lifetime and timestamp validation are defined by `telemetryMetrics`.

Keys are `social:metrics:v1:totals`, `social:metrics:v1:daily:YYYY-MM-DD`, and telemetry-managed dedup keys. The totals hash includes friendEdgesDelta/partiesDelta and baseline metadata. Current totals remain null until baseline initialization.

## Exact baseline without scanning production

First deploy counter hooks to every writer. Capture a consistent checkpoint after that deployment and restore it to an isolated instance with no writers. A pre-instrumentation checkpoint cannot account for changes made before hooks became active.

The prepared `.temp/social-deploy/count-social-checkpoint.cjs` requires localhost `CHECKPOINT_REDIS_URL`, `--ack-isolated-checkpoint`, and `--baseline-id`; it ignores production `REDIS_URL`. Only the isolated copy is scanned. It counts unique undirected friend pairs, rejects asymmetric friendship records rather than guessing, counts nonempty parties, and reads delta counters from the SAME checkpoint. Before/after size and counter checks supplement, but do not replace, the immutable-copy requirement.

Output shape:

```json
{"baselineId":"checkpoint-id","friendEdges":123,"parties":4,"friendEdgesDelta":7,"partiesDelta":2}
```

`scripts/init-social-metrics-baseline.js` applies that JSON in one atomic Lua operation, without scanning live data. It stores `baseline offset = snapshot count - snapshot delta`. The API computes `current = offset + live delta`, preserving changes after the checkpoint. An existing baselineKnown marker makes subsequent initialization a no-op. Historical daily counts are not fabricated.

Integration checks cover actual WATCH conflicts, callback aborts, duplicate friendship acceptance, every invite outcome, HTTP 401/403/success, retention, concurrent post-checkpoint deltas, and idempotent initialization. The offline counter fixture checks unique pairs, empty-party exclusion, snapshot deltas, and asymmetric-data refusal.

Baseline applied on 2026-10-06 from checkpoint `kvrocks-20261006T184513Z.tar.gz`, captured after auth25 hooks were deployed. The isolated restore had 11 social records, 10 user records, one unique symmetric friendship, and no nonempty parties. Snapshot deltas were both zero. Live initialization returned `baselineKnown=true`, one current friendship and zero parties. No production SCAN was used. The clone had `--network none`, published no ports, and was removed after verification. The deployed image excludes `scripts/`; initialization therefore invoked the same exported `socialMetrics.initBaseline` function through Node inside auth25.
