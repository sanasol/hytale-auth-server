# Official Hytale 0.7.0 server: Prop Hunt / Shuffle Jam code investigation

Date: 2026-10-13. Static analysis only; no server or game mode launched. No production changes.

## Artifact identity

- Source: `/Users/sanasol/Library/Application Support/Hytale/install/release/package/game/latest/Server/HytaleServer.jar`.
- SHA-256: `c3b5985b9e72727bf3bcf220923d7e61e78fe0f37cd1cd9c2a73f51e4f408ff8`.
- Manifest: version `0.7.0`, branch `release/release`, revision `648ebf375a034beabf119d7d790cd11d4f665ff4`, Java25.
- **Older `.temp/protocol-audit/HytaleServer-070.jar` was not this release:** version `0.7.0-pre.5.1`, revision `4371ccdc8822a826b4bbb8a84e180c521d716fd0`, SHA `35a34a32175cd92ce5e2a51310953db3a4a64cac89d995cbd4830c52a9b1b904`.
- Current Assets.zip identity and entry counts saved in `identity.json` beside this report.

## Findings and evidence ledger

Evidence paths below are relative to local `.temp/minigames-server-code/` in the parent workspace. Local evidence labels S1-S7 are analyst labels, not REA-generated IDs.

**S1 — Startup registration (observed decompiled control flow).** `decompiled/com/hypixel/hytale/server/core/HytaleServer.java:267` selects `Constants.CORE_PLUGINS` (or bootstrap subset) and registers each. `decompiled/com/hypixel/hytale/server/core/Constants.java:70` lists the core modules; there is no Prop Hunt, Shuffle Jam or matchmaking module.

**S2 — Builtin and external plugin loader (observed decompiled control flow).** `decompiled/com/hypixel/hytale/server/core/plugin/PluginManager.java:556` loads classpath `manifest.json` resources then `manifests.json`. The extracted `manifests.json` contains 64 builtin plugin manifests and explicit main classes, including Instances, GameFlags, Points, Model, Abilities, WorldEvents, and Adventure plugins. None is Prop Hunt, Shuffle Jam or a matchmaking service. Lines178-181 also load `mods/` and supplied mods directories. Lines445-475 enumerate external JAR files and load their manifests. Therefore a custom Java game plugin has an existing supported loading path.

**S3 — Actual GameMode API (observed source and assets).** `decompiled/com/hypixel/hytale/protocol/GameMode.java:9` has `Adventure(0)` and `Creative(1)`. This enum is NOT proof that custom modes are impossible. `full-decompiled/com/hypixel/hytale/server/core/asset/AssetRegistryLoader.java:344` registers a separate data-driven `GameModeType` asset store at `Entity/GameMode`. `decompiled/com/hypixel/hytale/server/core/asset/type/gamemode/GameModeType.java:38` defines its schema: spectator, movement, flight, no-clip, invulnerability, interaction overrides, HUD, etc. `decompiled/com/hypixel/hytale/server/core/modules/entity/gamemode/GameModeTypes.java:58` implements entering a custom type through lookup, cancellable enter events and state application. The shipped assets in `assets/Server/Entity/GameMode/` are Adventure, Creative, Spectator, SpectatorInteractive and HardcoreSpectator. They are player behavior presets, not round-based Prop Hunt or Shuffle Jam implementations.

**S4 — Instance hosting framework (observed source).** `decompiled/com/hypixel/hytale/builtin/instances/InstancesPlugin.java:148` registers commands, instance config, lifecycle events, timeout/world-empty removal and teleport interactions. `spawnInstance` at line200 loads a template `instance.bson`, assigns a UUID/config and delegates to `copyAndLoadInstance` (line276). Teleport helpers exist at line356. This is useful infrastructure for custom arenas but contains no Prop Hunt rules or shuffle-game scheduler.

**S5 — Exhaustive bounded name scan.** All 40,675 JAR entries were scanned as uncompressed bytes (not only filenames) for case-insensitive `prophunt`, `prop hunt`, `shufflejam`, `shuffle jam`, `matchmaking`: zero matches. `minigame` matched one class. Decompiled source resolves it at `decompiled/com/hypixel/hytale/server/core/modules/entity/repulsion/Repulsion.java:62`: a legacy repulsion config migration maps integer1 to asset ID `Minigames`; this is not a startup game mode. Full decompiled Hytale sources reproduce the same single relevant hit. 36,746 Assets.zip JSON/BSON/language entries were scanned for `prop.?hunt|shuffle.?jam|matchmaking`, with zero hits (`asset-search.json`). Generic Minigames assets remain present, including other demonstration resources; absence of these two modes does not mean absence of all minigame-related assets.

**S6 — Decompilation coverage.** All `com/hypixel/hytale/**/*.class` entries were copied into `hytale-code-only.jar` and processed by CFR0.152. Produced 6,829 Java files (53MiB) under `full-decompiled/`. CFR recorded 12 individual exception notes plus recovery warnings in `full-decompiled/summary.txt`. These are preserved verbatim; output is not claimed to be original source or fully recompilable. Focused decompilations are separately stored under `decompiled/`; the relevant classpath loader, startup registration and instance methods above are readable. Missing external dependency annotations were not treated as runtime failures.

**S7 — REA limitations.** REA `open_binary` identified archive type and the SHA above. `inspect_artifact` timed out after300seconds and produced no graph/Evidence ID. No archive graph conclusions are claimed. Archive session closed. Java code investigation used CFR rather than native Ghidra.

## Conclusion

High confidence for the inspected official release: **Prop Hunt and Shuffle Jam are not shipped as switchable standard server modes.** This is supported by the actual startup/plugin registration chain, mode schema/resources, full scoped class decompilation and whole-archive bounded scans, not filenames alone. Generic infrastructure is shipped and can host custom implementations.

A custom implementation can use a Java plugin in `mods/`, packaged assets/maps, instance creation/cleanup, player state/interaction APIs, events and UI. It still needs game-specific teams/rounds/win conditions/arena resets and (for Prop Hunt) disguises and abilities. To use the client's Minigames Play button, our backend additionally needs queue/ticket assignment, game-server capacity reporting and the successful connection contract. No official private server binaries or actual hosted official mode deployment were inspected, so this does not prove what their private infrastructure contains.

## Reproduce

From `/Users/sanasol/code/pterodactyl-hytale`, use the saved `hytale-code-only.jar` derived from the SHA above:

```sh
/opt/homebrew/opt/openjdk@21/bin/java -Xmx2g -jar .temp/minigames-server-code/cfr.jar .temp/minigames-server-code/hytale-code-only.jar --outputdir .temp/minigames-server-code/full-decompiled --silent true
rg -n -i 'prophunt|prop hunt|shuffle.?jam|matchmaking|minigame' .temp/minigames-server-code/full-decompiled
```

CFR downloaded from its author's `https://www.benf.org/other/cfr/cfr-0.152.jar`. Decompiled proprietary code and JARs are research artifacts only; do not commit them.

### Explicit CFR exception gaps

Twelve exception notes (11 distinct methods; NPCSpawn initializer reported twice): WeaponDamageDataCollector.build; Weather initializer; ScaledXYResponseCurve initializer; ScaledXResponseCurve initializer; FluidFX initializer; PluginManager.loadManifest; NPCSpawn initializer (twice); ConcurrentSingleFlightCache.get; PortalDeviceActivePage.createErrorPage; PortalDeviceSummonPage.createErrorPage; EnterBedSystem.getMessage. Additional nonfatal structuring/type-clash warnings are retained in summary.txt. In particular, PluginManager.loadManifest is not claimed as a fully recovered method; conclusions about plugin sources use its callers and the separately readable classpath loader plus manifests.
