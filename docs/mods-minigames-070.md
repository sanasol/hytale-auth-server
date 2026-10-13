# Mods and Minigames — client 0.7.0, 2026-10-13

## Observed contract

Official client session used locally for read-only API research; credentials are not stored here or used by the production adapter. Public response fixtures informed the schema. Runtime client requests subsequently confirmed URL encoding, query parameters and the rules PUT method.

Mods (`mod-browser.hytale.com`):
- GET `/api/categories`, optional `class=curseforge:9137` for children.
- GET `/api/mods` with `query`, `class`, `category`, `sortField`, `sortOrder`, `index`, `pageSize`.
- GET `/api/mods/featured`, `/api/mods/{provider:id}`, `/api/creators/{provider:id}`.
- GET `/api/mods/{provider:id}/files` and `/files/{provider:fileId}/download-url`.
- IDs include `curseforge:`; file hashes are named `sha1` / `md5`; download response is `{downloadUrl}`.

Production uses the CurseForge API with game ID 70216, configured through `CURSEFORGE_API_KEY_FILE` (or `CURSEFORGE_API_KEY`). No official user's session is proxied. Provider requests have an 8-second timeout, concurrency cap and bounded 60-second cache. Publisher distribution restrictions are preserved. Public provider totals can differ from the first-party catalog (6501 versus 6454 at research time).

Minigames (`matchmaking.hytale.com`):
- GET `/game-types?clientVersion=3908406161&lang=en-US` (decimal protocol version, not `0.7.0`).
- GET and PUT `/rules/acceptance`, response `{acceptedAt}`. First acceptance is stored per account in persistent Kvrocks without TTL.
- GET `/tickets/current`: our backend returns 404 with no active ticket; real client logs `No matchmaking ticket on login`.
- No game hosts are configured. All three catalog entries use `available:false`; queue creation returns 503. This is not a playable matchmaking implementation.

Prop Hunt and Shuffle Jam were not found as ready-to-run packages in the inspected 0.7.0 server JAR/archive entries. This bounded inventory does not prove the absence of every related implementation. Minigames Browse is a built-in Coming Soon page in client XAML.

## Client routing

F2P Evo's shared patcher must also handle the length-prefixed strings `https://mod-browser.`, `https://matchmaking.` and the image host prefix `live-content.`. Detection includes these strings even when the main domain was patched earlier. Plain UTF-16 searches miss these LP strings because their last character has no trailing null byte.

Catalog media are served from `/minigames/<catalog-hash>.png` on the auth host; only the 16 bundled catalog images are accepted by the route. Originals were fetched from the public `live-content.hytale.com/minigames/` URLs retained in `src/routes/minigames-catalog.json`. Responses use immutable caching. The client's image host allowlist also needs the content-prefix patch.

## Verification

- 18 focused Node tests cover API mapping, query parsing, download restrictions, caching, account authorization, rules persistence semantics, unavailable queues and bundled PNG responses.
- Production HTTP smoke covers roots/children, featured, search, details, author projects, files/download URL, catalog, rules read and unavailable tickets.
- Actual F2P client installed `BetterMap-1.3.9.jar` through Mods. Local file size 588682 bytes and SHA-1 matched provider metadata.
- Actual client PUT rules acceptance succeeded after correcting an initial POST assumption. Re-reading the acceptance returned the saved timestamp.
- API routing repair removed repeated current-ticket timeouts.
- After the content-prefix patch and restart, the actual client loaded Prop Hunt / Shuffle Jam card images; accepted rules were not requested again.

No full play session with the installed mod or playable Minigames host is claimed. Desktop clicks required user assistance because automated mouse events were ignored by the game.
