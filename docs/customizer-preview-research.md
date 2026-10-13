# Official Hytale 0.7.0 category tree and item preview construction

Static artifact research used to reconstruct the web customizer. Cached Ghidra database reused with `-noanalysis`, focused functions only. Original REA artifact identity evidence: ev_e07e2a80309264b25ef44b5ba532101987e1bd9a72a72591b5ea3db4e7f25106.

Artifact: `/Users/sanasol/Library/Application Support/Hytale/install/release/package/game/latest/Client/Hytale.app/Contents/MacOS/HytaleClient`, SHA256 `61814afc8aede3a1029f70be5d2f64b116cbf6f256b5af19c215d453084bfc06`. Addresses below are preferred Mach-O VM addresses. NativeAOT metadata parsing distinguishes tab enum from preview-part enum; The two enum mappings are documented below.

## Category hierarchy — answered

`Contents/Resources/Data/Game/Interface/MainMenu/MyAvatar/MyAvatarPage.ui` lines 125–129 provides visual main order: **Head, General, Torso, Legs, Capes**. Native 0x10054d130 provides membership, used by 0x100549b50 category visibility switch. Membership is independently proven rather than guessed from adjacent UI elements.

Visual subtab order (UI lines 170–192):

- Head: Haircut, Eyebrows, Eyes, FacialHair, HeadAccessory, FaceAccessory, EarAccessory.
- General: Underwear, BodyCharacteristic, Face, Mouth, Ears.
- Torso: Undertop, Overtop, Gloves.
- Legs: Pants, Overpants, Shoes.
- Capes: Cape.

Tab enum primary values: General=0, Head=1, Torso=2, Legs=3, Capes=4. Secondary enum values: BodyCharacteristic0, Underwear1, Face2, Ears3, Mouth4, Haircut5, Eyebrows6, Eyes7, FacialHair8, HeadAccessory9, FaceAccessory10, EarAccessory11, Undertop12, Overtop13, Gloves14, Cape15, Pants16, Overpants17, Shoes18. Membership mapping ranges: 0–4→General, 5–11→Head, 12–14→Torso, 15→Capes, 16–18→Legs.

Authentic icons are `Contents/Resources/Data/Game/Interface/MainMenu/MyAvatar/CategoryIcons/{Name}@2x.png` and `{Name}Selected@2x.png`. 0x100549c20 generates icon paths from frozen string `MainMenu/MyAvatar/CategoryIcons/` at 0x103d5d2a8 and enum names. Extract just these 5 primary + 19 secondary icons, preserving Selected versions. Various or other spare icons do not prove enabled categories.

## Item preview presets — answered statically

Main worker 0x100af8ca0 receives a request with preview-part enum at +0x38. This enum differs from tabs: Cape is 19 rather than 15. Literal NativeFormat enum field mapping is saved in category-enum-mapping.json. Numeric constants live in executable at 0x100af9ea0–0x100af9f78. All categories default to scale=1 and yaw=22.5 degrees unless stated otherwise.

| Preview category | Model translation (x,y,z) | Model yaw | Scale |
|---|---|---:|---:|
| BodyCharacteristic, Underwear, Undertop, Overtop, Gloves | (0,-4.9,-8) | 22.5° | 1 |
| Haircut, HeadAccessory, FaceAccessory | (0,-8.3,-8) | 22.5° | 1 |
| FacialHair | (0,-6.7,-8) | 22.5° | 1 |
| Eyebrows | (-.2,-8.3,-6.5) | 22.5° | 1 |
| Eyes, Mouth, Face | (-.2,-8.3,-6) | 22.5° | 1 |
| Ears, EarAccessory | (-1.2,-7.9,-3.6) | -22.5° | 1 |
| Pants, Overpants | (0,-2.5,-8) | 22.5° | 1 |
| Shoes | (0,-2.2,-8) | 22.5° | 1 |
| Cape | (-.2,-2.8,-8) | 202.5° | .7 |
| SkinFeature | No translation override in switch | default | 1 |

SkinFeature is not an enabled avatar subtab; omitted override must not be turned into a fabricated camera preset. The implemented numeric table is GAME_PREVIEW_PRESETS in assets/thumbnail-renderer.js.

Full-player preview request +0x3c is a separate path: T=(0,-3.8,-8), scale=.6, or T=(0,-5.25,-6.5) when +0x3d. The UI large AvatarPreview Scale=.88 (UI lines355–357) is not the tile scale.

### Exact units and transform meaning

0x100cf9a40 renders the assembled model. ARM 0x100cf9bdc–0x100cf9c00 reads target yaw +0x34/current yaw +0x38 and smooths with min(dt*10,1). At 0x100cf9c04 it builds Y-axis rotation from current yaw. At **0x100cf9c64–0x100cf9c6c**, it loads model scale +0x44 and divides by **12.0** before building matrix axes. This is CPU matrix construction, not a shader assumption.

Translation +0x58/+0x60 is read into the same model matrix (decompiled 100cf9a40.c lines183–185). Default projection is built in 0x100cf9260: diagonal 1/tan(pi/8), x additionally divided by width/height, thus **vertical FOV45°**. The alternate platform flag projection helper receives pi/4 and near=.1; its platform condition was not assigned a semantic name here.

For viewer authored coordinates scaled by .01, all native world coordinates scale by .12: authored*scale/12*.12 = authored*.01*scale. Thus translation should be native T*.12, model scale the category scale, origin camera facing -Z, FOV45°, aspect92/149. This translation replaces bounds-fitting/centering; it is not a camera-eye vector. Cape yaw is 180° more than default, intentionally showing its back. Native main model matrix shows no bounds center subtraction or added whole-model quaternion. A yaw quaternion separately constructed at cf9c4c is used later for attached render extras; it must not be applied again to the main body.

## Mannequin/base context — partially mapped, construction proven

0x100afa020 handles item context. Full-player request returns explicitly supplied skin +0x20 or current avatar. Normal item preview clones current avatar if available, otherwise default skin passed by registry owner. It allocates skin EEType0x1040f8c78 and copies only the first nine reference fields (offsets8,10,18,20,28,30,38,40,48 hex), not later clothing/accessory fields. Raw ARM is required here: Ghidra hides NativeAOT write-barrier arguments. BodyCharacteristic offset8 is independently mapped by 0x100979120. Exact names for the other eight private fields were not independently recovered from FieldAccessMap; mapping them to the remaining base facial/hair fields is an inference consistent with preview enum and usage, not separately named-field proof.

0x100cf9260 calls 0x100979120 for base body, 0x100977d00 for selected skin parts, and assembles models through 0x1009a4a40. Therefore the official preview is **not an isolated item mesh**: it retains base avatar context and merges the item into that model. Head shell alone cannot supply face/mouth/ears if those are separate cosmetic pieces. Using current/default avatar facial context is supported by actual construction; hardcoding an arbitrary mannequin is not exact parity.

## Search boundary / remaining verification

Static UI hierarchy, icon names, numeric presets, model-unit conversion and clone/assembly path are verified. Exact semantic names of eight cloned private skin fields and alternate projection platform flag remain unassigned. Pixel-perfect parity still needs a matched official screenshot with same current-avatar context, selected item, render dimensions, lighting, and animation state. No runtime capture was performed by this research pass. Decompilations in decompiled/*.c and raw ARM in ../avatar-071/100af8ca0.asm, ../avatar-071/100cf9a40.asm, ../avatar-071/100afa020.asm allow reproduction without repeating broad analysis.

## Web implementation and checks

The customizer now uses the recovered five groups and nineteen subcategories, plus normal/selected original icons. Item thumbnails use the native projection/model presets in normalized .01 units and the original tile aspect ratio. Base context is snapshotted per queued job and included in cache identity. Model/color/variant paths follow the current asset definitions; old category jobs are cancelled and pending loads are bounded. The renderer uses one full base body, avoiding duplicated Goblin/default geometry, and merges current base facial/hair context with the selected part. The base field semantic mapping remains the bounded inference noted above. Lighting remains the existing web rendering approximation.
