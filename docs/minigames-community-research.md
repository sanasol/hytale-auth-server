# Community minigames / official hosting research — 2026-10-13

## Official
https://hytale.com/news/2026/10/chapter-i-out-of-the-breach (October 12)
- Official hosted Prop Hunt and Shuffle Jam; 12 microgames in latter.
- What's Next section expressly describes downloadable local/friends minigames in Mod Browser as coming, and creator framework as a later release.
- This supports separation of hosted content from current downloadable server but does not itself prove archive absence; local code audit is separate.

## Prop Hunt downloads
https://www.curseforge.com/hytale/mods/spirits-prophunt
- Project 1528251, Spirit's PropHunt 1.6.0.zip, May 4 2026, Early Access, All Rights Reserved.
- Asset-editor implementation with map, hub item, team loadout totems, hunter crossbow, portal key, prop disguises and stun coins.
- Author states no Java coding; name tags require another mod, reset-player-model unreliable; compatibility with 0.7.0 not verified. More substantial playable prototype than PropHyte, no proven official menu API integration.

https://www.curseforge.com/hytale/mods/prophyte/files/7526501
- Project 1440012, GMod.PropHyte Alpha v2.zip, Jan25 2026,339.4KB, Early Access, All Rights Reserved.
- Description requires manual Creative Tools / Change Player Model. Start command/team interface/timer are planned. Not a turnkey automated round server.

https://github.com/Hy-Fi-Studio/Hy-Fi-Hide-and-Seek
- Inspected repository: README only,3 commits, no source/plugin release. Reject as implementation candidate despite marketing description.

No Shuffle Jam implementation/download surfaced in targeted web/CurseForge/GitHub repository searches. GitHub repository API queries hytale prophunt, hytale "prop hunt", hytale "shuffle jam" all returned0; this is limited search evidence, not proof none exists.

## Actual orchestration code
https://github.com/HiveScaleSystems/link
https://github.com/HiveScaleSystems/link/releases/tag/v0.1.0-beta.2
- MIT, actual Java source and tests, downloadable jar+SHA256SUMS; latest push Oct1 2026.
- /play group selects a server, signed single-use transfer tickets, static/Redis/HTTP registry, native referral, no proxy. Separate game implementation required.
- gradle.properties uses hytaleVersion=0.6.8; not verified against0.7.0. No evidence it implements official main-menu matchmaking REST/WS contract.

https://github.com/hyjn-nexori/nexori-plugin (default branch plugin)
https://www.curseforge.com/hytale/mods/nexori
- Actual source plus CurseForge jar v2.5.0 June5, labeled Hytale0.5; source available custom license, not open source.
- Native referral, queues, instanced matches, result reporting, optional custom backend, requires HyUI.
- gradle.properties server_version=2026.03.26-89796e57b. Need port/test for0.7.0.
- Examples: https://github.com/hyjn-nexori/nexori-capture-the-zone-minigame and https://github.com/hyjn-nexori/nexori-minigame-template

https://github.com/Liphium/hytale-matchmaking
https://github.com/Liphium/hytale-matchmaking-plugin
- Actual Go backend and Java adapter, matches/players/server routes, tests. January28 latest push, no release and no license detected. README warns early-stage and some needed plugins unpublished.
- Reference only; no demonstrated official 0.7.0 matchmaking compatibility.

No download/install/build/runtime test performed in this research task. The version and maturity judgments above are from publisher pages and actual repository manifests/source trees.
