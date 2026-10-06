# Server browser persistence (client 0.6.8)

Discover uses `https://santale.top/api/all-servers` by default. The adapter requests `online=true&f2p=true` before upstream pagination, rejects records unless both flags are explicitly true, then takes the first 100 eligible entries, transforms their IDs deterministically and applies Hytale filters locally. It is not the full 2396-entry upstream catalog (2396 / 24 pages observed 2026-10-06). `SERVER_DISCOVERY_SOURCE_URL` can supply an official-shaped JSON array instead.

Favorites and likes are account data: `GET /me/interactions/{favorite|like}?offset=N`, `POST /servers/{uuid}/interaction/{favorite|like}`, `DELETE` on the same path. Mutations and personalized reads require a valid session JWT. State lives in `social:v1:user:{uuid}.serverInteractions` and uses the shared isolated WATCH transaction. Existing `user:{uuid}.favoriteServers/likedServers` migrate on the first mutation; reads retain legacy compatibility. The old storage helpers remain for compatibility but are no longer called by this route.

`discovery:v1:listings` stores discovered card snapshots without expiry. Favorites can be returned even when a server leaves page one or the upstream is unavailable. Flags are overlaid per account, including official-shaped sources. Snapshots may have stale descriptions/counts; this is not a live server-health service. Missing legacy cards trigger bounded upstream lookup: at most 30 pages, four concurrent requests, 5-second request timeout, 10-second additional-page budget. A completed search caches absent IDs for five minutes. Unknown/deleted upstream cards without any historical snapshot cannot be reconstructed and are omitted, while their saved IDs remain intact. No recent-history upload API was observed.

Recently Played is local `RecentlyPlayedServers.json`; Private is local `Servers.json`, in the client's user-data directory. They are not synchronized through this backend. Read-only inspection found actual recent records in `/Users/sanasol/Downloads/Test1/RecentlyPlayedServers.json`.

Static evidence: Windows 0.6.8 SHA256 `05f476a25b967005b5480c46152c57811c0bc62aacfabbbff5a98d5e66043ba5`. GET interaction path builder `0x1409767a0`; POST path builder `0x1409769c0`; DELETE state machine `0x140ae7c20` loads HttpMethod DELETE at `0x140ae7c89` (static field +0x20). Enum mapping `0x140976d50`: 0=like, 1=favorite. Recent file references `0x1407006b5`, `0x14070076c`; Private file references load `0x140822c33`, `0x140822cd0`, save `0x140822f2d`, `0x140822fca`. Research assembly is under `.temp/rea-hytale/server-interactions-*`, `server-private-*` in the parent workspace. No new native UI verification is claimed.

Validation: disposable Redis integration test `tests/integration/serverInteractions.test.js` checks legacy migration, concurrent like/favorite updates, identity isolation, JWT rejection, personalized Discover flags, persistence from a separate connection, offline-source favorites, offset, idempotent removal and UUID validation.

Manual UI check after deploy: in account A favorite a Discover server; reopen Favorites and restart the client; verify it remains. Account B must not inherit it. Remove it and restart again. Join a server and verify Recently Played plus the local file. Add a Private server, restart and inspect `Servers.json`. The latter two validate native local persistence, not backend synchronization. Do not infer the origin of generic card artwork from these endpoint findings.

Production UI verification 2026-10-06: WsTestB1006 starred Avalon Hytale Survival
in Discover; Favorites displayed it. After fully quitting and restarting the
native client it remained starred in Discover and appeared in Favorites.
Removing the star emptied Favorites. Both changes reached the durable usage
counters (one add, one remove). The test left no favorite behind.
