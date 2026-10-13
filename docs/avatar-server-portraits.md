# Server portraits and multi-view inspection — 2026-10-13

`/avatar/:uuid/head` now returns a server-generated 128px PNG on a cache miss. `/avatar/:uuid/head.png` always requests that engine. Missing native dependencies or a failed job leave the existing browser fallback available on `/head`; `AVATAR_SERVER_PORTRAITS=0` disables native adoption. `nocache=1` deliberately retains the interactive browser diagnostic route.

`/admin/page/avatars` samples up to 30 actual saved skins, excludes server identities, selects 12 diverse characters and displays server/browser heads, front, right, back and full body on one page. Background and animation/frame controls help inspect alpha, clothing and poses. One browser renderer takes static snapshots; no per-row canvas or RAF loop. Admin player-list access retains the normal admin-token check, and this page does not change skins.

## Runtime isolation

One persistent worker thread owns one serialized GL context for the whole auth cluster. Cluster workers submit small skin JSON through the primary; the primary relays requests to the rendering thread. Archive extraction, decoding and rendering never run on auth request loops. A bounded 16-job queue rejects excess work; a 30-second active-job timeout terminates/replaces a stuck worker. Concurrent requests for the same skin deduplicate within a serving worker; the shared disk cache serves subsequent requests across workers.

Cache identity includes skin data, background, renderer code and asset archive version. Browser-uploaded images and native images use separate cache keys. PNG responses have ETags and conditional 304 support. Atomic rename publishes cache images so parallel readers cannot observe partial PNGs. Cache lifetime remains one hour.

The native engine caches parsed models, decoded/tinted raw RGBA textures, immutable material variants and geometry. Bounds: 256 models, 1024 materials, 2048 geometries, 1024 texture entries with a 32MiB raw pixel budget. These are cache bounds, not a promise that total RSS is 32MiB. Asset changes invalidate resources; evictions dispose GPU objects. Body depth-offset variants cannot mutate a shared head material or contaminate the next player's render.

## Measured evidence

Separate bounded Node processes in the production Linux image exercised real headless GL using saved production skins. The serving app was not altered for those measurements.

| Measurement | Previous engine | Current final engine |
|---|---:|---:|
| Warm 128px render | 4.2–4.7 seconds | 99ms median, 46–191ms |
| Cold final sample | 8.4 seconds first baseline | 1845ms median, 984–5022ms |
| GL contexts | one more every portrait | one throughout |
| Final 12-player run | — | 24/24 cold + warm successes |
| Isolated renderer RSS | 203→219MiB after four jobs | 188–234MiB after 24 jobs |

The final sample covers regular, muscular and goblin bodies, short garments, masks, eyes, hats and capes. It establishes representative behavior, not unlimited throughput under hundreds of unique simultaneous skins. Worker-thread smoke testing also verified rendering does not stop a timer in the calling process, and a four-process cluster successfully shares one rendering worker.

## Rendering correspondence

Game CPU evidence in [avatar-animation-research.md](avatar-animation-research.md) establishes piece-root binding, offset/pivot order, `rest * animation` quaternions, shortest-path NLERP and the weighted rational smooth curve. Matching pieces transfer children without creating their own mesh or deleting the target body mesh. Partial clothes retain base skin. Shipped alpha and greyscale/gradient behavior is preserved.

Native textures use raw DataTexture and explicit legacy/linear settings to avoid an unintended extra sRGB conversion. Native and browser use the same portrait camera and light configuration. The local `DepthPeelingRenderer` name is misleading: that class implements ordinary object-distance sorting, not a real multi-layer peeling pass. Most alpha-tested avatar meshes use its ordinary Three render path. Native also uses ordinary Three transparency with depth testing/writing; per-category eye/face ordering hacks were removed.

Transparent PNG output converts premultiplied framebuffer RGB into straight alpha before encoding. A real GL red fixture returned `(128,0,0,128)` on transparent, `(128,0,0,255)` on black and `(255,127,127,255)` on white; the corrected transparent PNG is `(255,0,0,128)`. A real player portrait also contained alpha-1 pixels requiring this conversion. Opaque backgrounds remain unchanged. All twelve sampled transparent and white portraits rendered successfully.

Pixel-exact game correspondence is still unproven. The shipped engine has additional transparency/environment/postprocess branches, track mixing, stretch/UV animation and category visibility paths not fully reconstructed. These limits are separate from the now-proven rigid transforms and attachment behavior.

## Deployment verification

Rolling deployment on 2026-10-13 used a second healthy container before retiring the previous one. Six concurrent real HTTPS portrait requests returned PNGs; `/head` returned the same cached engine PNG. During cold queue work, all 31 sampled `/health` responses were 200 (69ms median, 117ms maximum including network). Cached `/head` requests took 59–80ms including network.

Initial `.png` requests exposed Cloudflare's default image caching. Native PNG responses now explicitly prevent CDN storage and require browser revalidation; gallery image URLs carry the renderer version. This prevents CDN caching from masking skin/cache invalidation. Older CDN objects created before these headers can live until their prior TTL; versioned gallery URLs avoid those objects.
