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

## Verified implementation

Removed synthesized eye-shadow textures and category-specific depth translations in
all three renderers. Original eye whites and alpha are retained. Body calf geometry
is kept under shorts; Forest_Bermuda has a 7-pixel garment box over a 24-pixel calf,
so hiding the whole calf removes exposed skin. Browser front/side checks confirm
restored calves and eye whites. Full native CPU attachment/OIT parity remains a
separate unfinished research question.
