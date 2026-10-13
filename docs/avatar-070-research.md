# Hytale 0.7.0 avatar assembly investigation (2026-10-13)

## Identity and coverage

Fresh official installation: `/Users/sanasol/Library/Application Support/Hytale/install/release/package/game/latest/Client/Hytale.app`.
REA `open_binary` resolved arm64 native executable `Contents/MacOS/HytaleClient`, SHA256 `61814afc8aede3a1029f70be5d2f64b116cbf6f256b5af19c215d453084bfc06`. There is no managed client DLL in the shipped Mac bundle. Native `search_strings("Cosmetic")` timed out at 300 seconds while Ghidra initial analysis remained unavailable; no REA Evidence ID or native cosmetic pseudocode was returned. Session closed. Do not call this a complete client-code decompilation.

Evidence used instead: exact shipped `Data/Game/Schema/PlayerSkin.json`; current official `Assets.zip` cosmetic JSON and blockymodel resources; fresh 0.7.0 server reconstruction in `.temp/minigames-server-code/full-decompiled`; and actual importer source of Hytale's Blockbench plugin, fetched from https://github.com/JannisX11/hytale-blockbench-plugin/blob/main/src/blockymodel.ts into this report directory. Selected resources are saved under `resources/`. These are static observations, not observed client execution.

## Answered: identifier and resource selection

- Shipped schema states `partId.colorId` or `partId.colorId.variantId`; required skin fields are bodyCharacteristic, underwear, face, ears, mouth, eyes. All accessory/clothing fields nullable. Exact server reconstruction `CosmeticsModule.java:245–255` splits with empty fields preserved and checks token 2 as variant, token 1 as texture/gradient.
- `PlayerSkinPart.java:35–63` chooses Variants before Textures; variant data has its own Model, GreyscaleTexture, Textures. GradientSet is parent data. `CosmeticsModule.java:230–237` accepts a gradient first, otherwise chosen variant's textures or top-level textures.
- Fresh BodyCharacteristics: Default, Muscular, Goblin; all use `Characters/Player.blockymodel` with respectively `Player_Greyscale.png`, `Player_Muscular_Greyscale.png`, `Player_Goblin_Greyscale.png`. Browser hardcoded Regular/Muscular texture selection therefore loses Goblin.
- Fresh eyes have multiple models, including Eyes_Shiny and Eyes_HalfPixel. Use selected resource, not one synthesized eye geometry.

## Answered: attachment and offset semantics

Official Blockbench importer lines 515–524 attaches a node by matching name ONLY when `shape.settings.isPiece === true`. Browser currently matches every named node, which can reparent local children incorrectly.

Importer lines 539–545: bound attachment root origin is main shape's origin (mesh center), ignores authored absolute node position. Root bound rotation becomes zero at lines 561–565. Non-bound children add parent group origin AND parent shape offset. Shape geometry center is own origin + own offset (lines 602–618). Recursive parsing passes offset to children (line 779).

Fresh official server `BlockyModelBoundsParser.java:90–158` corroborates offset inheritance: localPosition = node.position + rotation(node.shape.offset), computes worldPosition from parentPosition and parentOrientation, and recurses children with worldPosition. Removing shape.offset inheritance globally is incorrect. For animation-correct implementation use pivot group + mesh-center group; children are relative to parent mesh center; animation rotation pivot is separate. Current browser's center-only representation is adequate for some static cosmetics but imperfect for animated offsets.

Concrete Player data: Belly position y4/offset y8; Chest position y5/offset y11; Head position y8/offset y20. Sum of positions alone gives Head y68; including inherited centers gives Head center y107 and eyes y105.2. Cosmetic root Head authored y79 is ignored when bound. Face child position (0,-20,12), own offset (0,20,2), hence center relative bound Head (0,0,14). Eye attachment roots contain authored full-model coordinates which must be ignored upon binding.

## Answered: missing faces and UVs

Player Head's textureLayout has back/right/left/top/bottom and **no front**. A front face generated with default Three.js UVs samples the full player atlas and can overlay detached face with blue/black strips. Official importer lines 665–689 clears absent faces (`texture=null`), confirming missing face layout means no rendered face. Browser should omit missing faces rather than retaining default box geometry.

Quads always consume textureLayout.front even if normal is ±X/±Y/−Z; importer lines 679–685 maps quad normal then takes front layout. Rotated UVs use dimension/mirror swapping for 90/270; copy format math rather than rotating normalized texture coordinates without dimension changes.

Eye model separately includes Background quads (first texture row) and eye quads (second texture row). Left background uses negative shape stretch and UV mirroring. `shape.stretch` is geometry stretch, not inherited skeletal scale. Do not apply stretch on bone groups. These observations support vertex probes but do not establish the game's alpha blending/shader behavior.

## Answered: cosmetic compatibility and hiding

14 current entries have HidesSkinProperties. Blindfold, BandageBlindfold, BanditMask hide Eyebrows. Hoodie, HeadphonesDadCap, Headphones, LeatherCap, Hoodie_Feran, Hoodie_Ornated, Hood_Blazen_Wizard, GoblinCostume_Mask, TrapperHat, GoblinThief_Hood hide Ears and EarAccessory. Head_Bandage hides Haircut. Hide these cosmetic slots before building, not only body geometry.

13 entries have FallbackPartIds (old IDs → current entry): SpikyMohawl → SpikyMohawk; Face_MakeUp_HeavyEyeliner → Face_MakeUp_Mascara; Mouth_Makeup → Mouth_PronouncedLips; GaiaPure_Gloves/GaiaPure_Crown/GaiaPureOvertop/GaiaPureSkirt/GaiaPureBoots → Hope_Of_Gaia items; BasicShoes_Strap/BasicShoes_Buckle → MaryJanes; QuiltedLeatherJacket → Jacket_Voyager; QuiltedBoots → Boots_Voyager; Tshirt_Logo → TShirt. Alias direction inferred from the current data field and names; verify against a stored old skin before rewriting persistence.

HaircutFallbacks.json maps Short/Medium/Long to GenericShort/GenericMedium/GenericLong. Fresh server `CosmeticsModule.java:267–279` resolves a HalfCovering hat plus haircut requiring generic to Generic + HairType with original haircut color retained. FullyCovering remains valid on server, so server validation alone does not prove client hides the haircut. Implement browser replacement from explicit cosmetic metadata and verify UI behavior.

## Partially answered / unknown

- GradientSets supplies PNG lookups and BaseColor; native shader channel/multi-row mapping was not recovered. Existing browser greyscale-only gradient remapping should be treated as approximation until verified against official renderer.
- Precise client alpha/transparency/lighting, hidden body geometry rules beyond explicit HidesSkinProperties, animation attachment pivot matrices and render order remain unverified by native client code.
- No screenshot comparison or runtime client avatar capture was performed in this delegated task; parent performs browser verification.

## Implementation priorities

1. Remove absent box faces; honor isPiece for binding; preserve offset inheritance.
2. Resolve body characteristic texture from shipped data, including Goblin; honor exact variant texture/model and explicit HidesSkinProperties.
3. Preserve fallback aliases and generic haircut compatibility without silently altering stored skins.
4. Add fixture probes for Head-without-front, detached Face, Eye backgrounds, rotated/mirrored quads, Goblin, half/fully covering hats. Use actual shipped models.
5. Do not advertise exact parity or full native decompilation until shader and animation gaps are measured.

## Browser/backend corrections and verification

Implemented missing box-face omission and `isPiece` attachment rules in browser,
thumbnail and native rendering; body metadata selects Goblin texture. Shared
cosmetic compatibility hides declared slots and selects generic haircut under
HalfCovering accessories. Old cosmetic aliases resolve without rewriting raw skins.

Editor: item-specific palettes, inherited variant data, eyeColor consistency,
explicit nulls for removed accessories, parsed Discard snapshots, serialized
preview rebuilds and GPU resource cleanup. Save requires an admin session or an
unexpired verified owner session. Portrait links in admin open the editor.

Head cache: versioned by renderer/assets and skin, fixed 128px images, background
preserved, PNG/size validation, stale upload rejected, conditional revalidation.
Docker now includes depth-peeling.js. Production previously served avatar.js
SHA256 31d5a6ca5ab408ce55ee5400cd002209509c5a1906ca56d3b092690b638e1354,
which differed from source HEAD, so the complete asset bundle must be deployed.

Checks: 80 scoped Jest tests; 15 Node regression tests for geometry, attachments,
cache invalidation, save authorization, removal/color serialization, Discard and
preview races. Fresh 0.7 ZIP item/variant descriptor sweep: 897, no descriptors missing model
or texture fields. Four real saved skins rendered in a browser from the fresh ZIP.
Exact native-client shader/animation parity remains outside proven coverage.

## Follow-up: reported eye transparency, missing calves and floating face

Actual fresh client BlockyModelFS shader was recovered from the Mach-O byte range
0x25e7eda–0x25eb95a. It preserves sampled alpha, discards alpha zero, and applies
gradients only when R=G=B. See avatar-native-render-research.md for binary identity,
REA evidence, extraction offsets and remaining CPU-method reconstruction gaps.
The original opaque eye-white texture is now used directly; the synthesized black
shadow/alpha texture was removed in all three renderers. Partial PNG alpha is kept.

Model-authored coordinates put DetachedFace flush with the head front. Artificial
category Z offsets moved face 1 asset pixel and eyes 3 pixels; those offsets were
removed. The authored eye background/pupil gap remains 0.1 pixel. Shape center
offsets remain inherited, as required by the actual blockymodel hierarchy.

Pants no longer hide all base thighs/calves. Fresh shorts leave calf skin exposed;
retain body geometry with polygon depth offset for overlapping garment surfaces.
Browser screenshots of the real Forest_Bermuda skin confirm both calves and eye
whites, front and side. This does not establish exact native CPU replacement masks,
animation pivot matrices or the active native OIT branch.

## Production verification

Deployed with the rolling auth script on 2026-10-13; container
`traefik-hytale-auth-37` is healthy. Public avatar.js, thumbnail-renderer.js and
depth-peeling.js match local bytes. Generated head is a 128x128 PNG and refreshes
with renderer/assets/skin identity. Admin portrait URLs carry the renderer version
so browsers cannot keep the old cached image after an update.

A disposable UUID verified actual production editor PUT, accessory removal via
null, saved Goblin body and saved eye color; all returned/persisted correctly.
Disposable user/token keys were cleaned up. Unauthenticated editor save returns
401. Focused checks: 80 Jest tests + 20 Node renderer/cache/editor tests passed.
