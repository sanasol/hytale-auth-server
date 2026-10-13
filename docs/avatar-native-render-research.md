# Fresh client rendering code evidence — 2026-10-13

## Identity and reconstruction path

Official Mac client Hytale 0.7.0: `/Users/sanasol/Library/Application Support/Hytale/install/release/package/game/latest/Client/Hytale.app/Contents/MacOS/HytaleClient`, SHA256 `61814afc8aede3a1029f70be5d2f64b116cbf6f256b5af19c215d453084bfc06`. REA `inspect_macho` Evidence ID `ev_e07e2a80309264b25ef44b5ba532101987e1bd9a72a72591b5ea3db4e7f25106` confirms arm64 Mach-O. Session closed after inspection.

This build is native compiled .NET/AOT: `__managedcode` file offset0x175a40 size0x2215ccc, `hydrated` zero-file-data section VM0x103c9e340 size0x10f9bb8, `.dotnet_eh_table`, and compressed reflection names. It does NOT ship a managed client DLL suitable for ILSpy. LC_FUNCTION_STARTS contains226824 starts. Existing Windows0.6.8 target is also native and is not evidence of current0.7.0 behavior. No current0.7.0 Windows client was downloaded in this scope.

New productive route: literal GLSL shader sources are embedded uncompressed in current native executable. They can be extracted directly without waiting for Ghidra autoanalysis. Local extraction used raw immutable bytes; no game execution or binary patching. Native function search previously timed out300s and was not repeated.

## Actual shipped BlockyModel fragment/vertex shader

Embedded resource names:
- fileoffset0x2679e99: `HytaleClient.Graphics.Shaders.BlockyModelFS.glsl`
- fileoffset0x2679f13: `HytaleClient.Graphics.Shaders.BlockyModelVS.glsl`

Source blocks identified by matching uniforms and references:
- fragment source bytes0x25e7eda through0x25eb959 → `shaders/BlockyModelFS.raw.glsl`;
- vertex source bytes0x25eb95a through0x25ed61e → `shaders/BlockyModelVS.raw.glsl`.

Extraction boundaries are next `#version` markers. Blocks may include trailing shared helper text; these are readable source bytes, not CPU-function decompilations. Resource-name-to-byte-block correspondence is inferred from unique shader contents and embedded names, not parsed resource-directory indexes.

Fragment lines190–200 samples an atlas texture, multiplies alpha only by global transparency, and discards when texel alpha equals zero. It explicitly notes use for character/item previews with orthographic projection. It does not replace eye-background opacity in this generic shader.

Fragment lines264–268:
```glsl
bool isGreyscale = (texel.r == texel.g) && (texel.g == texel.b);
if (isGreyscale && gradientId > 0) {
    ivec2 coord = ivec2(texel.r * 255, gradientId - 1);
    color.rgb = texelFetch(uGradientAtlasTexture, coord, 0).rgb;
}
color.rgb *= tintColor;
```
This establishes strict channel equality, no arbitrary white-preservation rule, and lookup affects RGB only. It does not establish which CPU code uploads a per-face gradientId.

Fragment lines255–259 supports a USE_ALPHA_BLENDING shader branch; lines353–362 invoke processOIT with BLEND_MODE_LINEAR. OIT include in full extracted blob `shaders/0x25e3725.glsl:7555–7740` supports WBOIT, extended WBOIT, moment transparency, and K-buffer; linear blending premultiplies RGB by alpha. Which OIT branch the avatar preview chooses was not recovered. Browser ordinary alpha/depth passes can reproduce opaque eye pixels, but are not proven full OIT parity.

Vertex lines99–106 performs `nodeMatrix * vec4(vertPosition,1)`, using uploaded per-node matrices. Lines164–165 use atlas pixel coordinates and flip V. This confirms coordinate pipeline but does not recover CPU matrix construction or isPiece attachment remapping.

## Eyes: opaque background data and original alpha

Fresh `Eyes.json` Medium_Eyes declares `Eyes.blockymodel`, Greyscale.png, GradientSet Eyes_Gradient. Model Background nodes map texture first16rows; pupils map second16rows. Selected source PNGs saved in `resources/Medium_Eyes.png`, `Large_Eyes.png`, `Cat_Eyes.png`, `Demonic_Eyes.png`.

Medium/Large/Cat32x32 background region contains392 opaque255 pixels and120 transparentborder pixels. Frequent RGB156,157,171 is not grayscale, so generic model fragment code preserves original color. Medium pupil region contains448 transparent pixels and64opaque pixels, grayscale pupil pixels receive eye gradient. Demonic background is mostly grayscale and therefore can receive eye gradient.

Our `_createEyeShadowTexture` zeroes RGB and synthesizes alpha0.25 in the first4rows, discarding the shipped opaque eye-white layer. This directly contradicts original data+fragment behavior. Use original selected eye texture for both Background and pupil quads, preserve its alpha; do not synthesize a universal shadow background. No claim is made that there is zero CPU-side texture preprocessing without recovering its code.

## CPU assembly visibility: discovered anchors and unresolved mapping

Native reflection names observed:
- CharacterPartId / ClientPlayerSkin aroundfile0x24a9393;
- CharacterAttachment / CharacterHaircutPart around0x24a959d;
- HidesSkinProperties at0x253de99;
- GetEyes at0x253e348; GetCharacterModel lambda0x25a06b8;
- TryGetCharacterAttachment lambdas0x25a03a4;
- LoadPlayerSkinModel / LoadAttachmentsFromSkin lambdas0x25d47af.

These are **metadata/string locations**, not callable method addresses. Focused ADRP+ADD literal-reference scanning did not resolve these to managed methods; AOT hydrated data/metadata prevents straightforward string→function remapping. Script `native-string-xrefs.py` retained for reproducibility. Do not label guessed metadata offsets as game-function addresses.

Precise client per-node replacement/body visibility and attachment transform code remains unresolved. Shorts with empty calf attachment nodes cannot justify unconditional hiding of calf meshes from resource data alone; the actual geometry distinction is measurable, but recovering current CPU replacement method needs a targeted NativeAOT metadata/hydration reader or no-autoanalysis native disassembly seeded by authenticated method metadata. Static Blockbench behavior is separate corroboration, not a substitute for that missing client code.

## Checklist

- Eye texture/alpha fragment behavior: answered by shipped shader+PNG.
- Gradient equality and alpha preservation: answered by shipped shader.
- Exact avatar OIT branch: partially answered; available branches observed, runtime selection unknown.
- Detached face CPU piece binding/matrix construction: unresolved in current native methods.
- Shorts CPU body mesh replacement: unresolved in current native methods.
- Current Windows managed fallback: no managed client DLL established; old0.6.8 binary excluded.

## Follow-up: NativeAOT CPU method mapping recovered

The earlier name-to-function limitation was partly resolved with a bounded static parser; no target execution, no repeated full Ghidra autoanalysis.

`map-aot-methods.py` implements .NET NativeFormat unsigned decoding and follows authenticated immutable byte coordinates: ReadyToRun header file0x3c9c9f0 major16 → Metadata313 file0x239b520..0x25e3725 → InvokeMap306 file0x26a18cc..0x285d185 → CommonFixups308 file0x2aed464..0x2b737c0. Runtime v10.0.0 source matches major16; v8/v9 flag layouts differ and were excluded. Sources downloaded locally: ModuleHeaders.v10.h, MappingTableFlags.v10.cs, NativeFormatReaderGen.cs, MdBinaryReaderGen.cs, ExternalReferencesTableNode.cs.

Verified GetEyes chain: native string recordfile0x253e347 (header+GetEyes) → methodrecordfile0x24a9af6 (flags0x86, implementation0, namehandle0x1a2e27) → InvokeMap recordfile0x273ba2a (flags0x20, methodoffset0x10e5d6, owningTypeIndex0xb08, entryIndex0x6019) → CommonFixups pointerfile0x2b054c8 signedrelative-0x218e418 → **functionVM0x1009770b0**. ARMassembly GetEyes.asm and Ghidra pseudocode decompiled/1009770b0.c retained. This getter builds a selector over registry fieldoffset0x48; it is not body-part replacement code.

Additional actual mapped methods/lambdas:
- GetFace0x100977020, GetMouth0x100976f90.
- GetEyes closure b__0 native thunk0x100046528 → body0x100c220e0. GetFace closure0x100046524 shares body. The body compares selected part string IDs: pointer equality, null/length checks, then UTF16 byte comparison. This supports resource-by-ID selection only.
- TryGetCharacterAttachment b__93_0/1/2 native thunks0x100046500/504/508 → body0x1002d0de0 → tail-call0x1001aa4a0. All three use shared code. Decompiled reconstruction copies an attachment object, including optional collections and flags; it does not establish piece binding transforms or base mesh hiding.
- GetCharacterModel closure b__0 function0x100cc3ed0 (metadatarecord0x253e2aa, owningTypeIndex0x135b, entryIndex0x80b4). Reconstruction copies model texture info, changes integer offsets and shading data. It is not the primary assembly method.
- LoadPlayerSkinModel closure b__345_0 function0x100c933e0, LoadAttachmentsFromSkin b__348_0 function0x100c93450 (sort comparator on int fieldoffset0x20), closure b__1 function0x100c93700 (texture-info copy/offset update).
- ResolveHiddenCategories closures0x100cc3ac0,0x100cc3b00,0x100cc3e30,0x100cc3e60,0x100cc3e90 mapped. Names concern cosmetic category masking, not proven skeletal mesh replacement.

Targeted Ghidra import with -noanalysis succeeded, saved project `research/ghidra-project/avatar-070`; four named helper addresses decompiled successfully in `decompiled/`. Thus fresh target now has reusable imported database without mandatory300second autoanalysis. Note Ghidra transparently resolves tail-call thunks when reconstructing their function; raw ARM assembly remains canonical branch evidence.

`rehydrate-aot.py` statically reconstructs actual .NET10 dehydrated data section207, using runtime StartupCodeHelpers.RehydrateData semantics. Command stream consumed exactly file0x2c5e1e1..0x348fb70, output VM0x103c9e340..0x104d97ef8,17800120bytes matching hydrated section size. This is static reconstruction, not a dumped process. No direct absolute pointers to selected thunk addresses found in reconstructed data; it did not immediately identify primary assembler.

Bounded remaining gap: primary GetCharacterModel/TryGetCharacterAttachment/LoadAttachmentsFromSkin methods are absent as plain NativeFormat method names in retained reflection metadata (only their compiler-generated closures remain). Direct BL and ADR/ADRP literal reference searches for mapped closures did not recover an unambiguous primary parent method. Exact native piece/bone matrix assembly and per-bone body visibility remain unresolved; shader and selector/clone code is actual current game code, but should not be presented as proof of full assembly parity.

## Verified implementation

Removed synthesized eye-shadow textures and category-specific depth translations in
all three renderers. Original eye whites and alpha are retained. Body calf geometry
is kept under shorts; Forest_Bermuda has a 7-pixel garment box over a 24-pixel calf,
so hiding the whole calf removes exposed skin. Browser front/side checks confirm
restored calves and eye whites. Full native CPU attachment/OIT parity remains a
separate unfinished research question.

Reproducible, SHA-bound CPU mapping scripts are saved under `research/avatar-070/` next to this report. They read the supplied native executable without executing it. Required analysis packages for the mapper: Python, lief and capstone. Hydration uses the Python standard library. Extracted binaries, shaders and Ghidra database are kept locally and are not committed.
