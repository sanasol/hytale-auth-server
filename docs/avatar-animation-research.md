# Hytale 0.7.0 CPU model assembly investigation

Target: official installed arm64 NativeAOT HytaleClient, SHA-256 `61814afc8aede3a1029f70be5d2f64b116cbf6f256b5af19c215d453084bfc06`. Same target and validated NativeAOT v10 hydration as avatar-070. All addresses below are preferred Mach-O virtual addresses; ASLR slide is excluded. Static evidence from shipped game code; viewer regression fixtures verify the reconstructed formulas.

## Actual game assembly, beyond reflection names

Static hydration restored frozen UTF-16 string objects. `Couldn't find attachment target: {0}` at `0x103cfc3b0` has an ADR reference at `0x1009a4e68` within function **0x1009a4a40**. This is genuine attachment traversal, not a reflection getter/clone. Its caller **0x1007422a0** sorts/selects cosmetics via `0x100977d00`, looks up their parsed models in cosmetics-registry field +0x118 and atlas entries in +0x110, applies texture metadata via `0x1009a59c0`, then calls the traversal. This is the recovered LoadAttachmentsFromSkin-like path, though its original method name is not independently retained.

Node layout has independent debug-name confirmation: `BlockyModelNode` ToString **0x1009b0280** calls record printer **0x1009b0450**. Debug output explicitly associates +0x24 with Position, +0x30 Quaternion/Orientation, +0x40 Size, +0x4c Stretch, +0x58 Offset, and **+0x22 with IsPieced** (literal string 0x103cb5968, characters begin +12). Copy constructor **0x1009b1270** copies these fields separately. This is game CPU code evidence, not Blockbench convention.

### Piece traversal proven branch

`0x1009a4a40` copies the list of existing parts, appends the new parts, and traverses newly added nodes through a queue carrying `(partIndex, nodeIndex, hasParent, parentPart, parentNode)`.

```text
if explicitAttachmentTargetAlreadyResolved OR !node.IsPieced:
    register parent relation for current node
    enqueue its children under current node
else:
    if existingModel.nameToNode.TryGetValue(node.NameId, out target):
        enqueue piece children under target
        // skips registration of the piece node itself in this branch
    else:
        resolve diagnostic name; log "Couldn't find attachment target"
        fall back to normal node/child handling
```

Raw ARM anchor **0x1009a4d0c** branches around piece resolution if explicit attachment resolved; **0x1009a4d10** `ldrb w0,[x3,#0x22]`; **0x1009a4d14** branches to normal handling if false. Matched-piece loop requeues children using target at +0x8c/+0x88; normal branch begins **0x1009a4e74**. Raw assembly and Ghidra C are adjacent artifacts.

**0x1009a3e20** constructs the model's name-to-node table: nodes with IsPieced=false use dictionary insertion mode `1`; IsPieced=true use mode `0` (observed last argument), giving ordinary node names priority over placeholders. Dictionary mode interpretation is inferred from call semantics, while distinct branch/argument values are directly observed.

This proves a useful rule: a matched piece is a placeholder/bind point and its authored node transform is not inserted as an additional parent. An empty matched L-Calf placeholder has no children to reparent. This traversal does not delete the existing calf node or mesh. Therefore deleting base calf merely because pants contain an empty `isPiece` L-Calf is unsupported. It does **not** prove the absence of a later GPU geometry visibility filter.

## Separate explicit visibility paths

Equipment attachment loader **0x100741ce0** calls the same traversal, then folds a per-asset byte array at asset+0xa0 -> +8 into visibility/category bits. Observed mapping enum byte -> bit: `0:1,1:4,2:8,3:16,4:32,5:64,6:128,7:256,8:2,9:512,10:1024,11:2048,12:4096`. Its caller **0x100741220** tests these bits when selecting hair/beard/ears and other asset paths. Original enum/category names are not independently reconstructed, so this table should not be labelled a body-part mask without more mapping.

Cosmetics path **0x1007422a0** has a separate flag at selected-attachment descriptor +0x24. When true, it constructs a special two-node model/parent relation before merge. That flag's original field name is not yet proven; do not equate it with body replacement, IsPiece, or eyes until mapped to descriptor debug/metadata.

## Resolved CPU transform order

The UBO route recovered **0x1007c35a0**: attachment merge -> render-model constructor **0x100838320** -> update **0x100839d00** -> upload renderModel+0xb0 bank (`count<<6` bytes). Update loops node references and calls **0x100839dc0**. That procedure obtains parent from the model adjacency map and computes a per-node cached Position+Quaternion record at renderer+0x58, stride0x1c, then writes output matrix bank renderer+0x50, stride0x40.

Per-node helper **0x10083ad40** initializes cache from the independently identified BlockyModelNode fields:
- raw **0x10083ae34** reads Position (node+0x24), copies3floats;
- raw **0x10083ae50** reads Orientation Quaternion (node+0x30).
- translation track interpolated delta is **added** to rest position (C lines155–178).
- quaternion track interpolation uses hemisphere sign flip from dot product, linear `(1-t)*q0+t*q1`, then normalized by sqrt. Raw **0x10083b0c8 / 0x10083b0cc** FSQRT/FDIV establish normalized lerp, no trigonometric SLERP in this keyframe branch (C190–224).
- interpolation result is multiplied into rest orientation as **Qrest * Qanimation**. SIMD constants file0x83bbe0/0x83bc00/0x83bc10 are signvectors `(1,-1,1,-1)/(1,1,-1,-1)/(-1,1,1,-1)` and permutations0x83bbf0/0x83bc20, reconstructing Hamilton product in reversed operand convention `multiplyKernel(a,b)=b*a`. This was accounted for when translating the formula; do not read the decompiler operand order naively.

Node **0x100839dc0** then reads shape.Offset and rotates it by the just-computed local quaternion: raw **0x10083a418..0x10083a4d0** reads node+0x58, expands `(offset,1)` and adds quaternion-conjugate sandwich rotation to local Position. The kernel takes `conjugate(q)` as first operand, vector second, then q, giving `q * vector * conjugate(q)` after the reversed operand convention above. Parent composition follows (C811–898); local center is rotated by cached parent quaternion, parent center added, and local quaternion multiplied by parent quaternion.

Handwritten reconstruction for the regular animated-node branch:
```text
localQ = restQ * animationDeltaQ
localP = restPosition + animationDeltaPosition
localCenter = localP + rotate(localQ, shapeOffset) + optional extra local translation
worldCenter = parentCenter + rotate(parentQ, localCenter) // or localCenter for root
worldQ = parentQ * localQ                              // or localQ for root
matrix = rigidMatrix(worldCenter, worldQ)
```

Thus **own rotation happens around authored node position, own geometry is shifted by rotated shape.offset, and children inherit parent shape-center position**. Split named pivot at node.Position + rotation, with shapeCenter child at shape.Offset containing both own mesh and child nodes, reproduces this rigid-transform order. Binding an isPiece's children to existing target shapeCenter avoids an extra authored placeholder transform.

Scaling/stretch animation and category overrides also appear in this routine and remain separate from the rigid order above. This is not a blanket claim of complete animation/render parity. The native branch for node.Visible=false (field+0x20, independently labelled `Visible` in ToString) writes zero upper matrix rows, retaining bottom row/translation; browser hide geometry while maintaining descendant transforms is the practical counterpart. IsPiece handling and explicit visibility are separate fields/branches.

## Resolved keyframe easing / sampling

Quaternion sampler **0x101aae140** and Vector3 sampler **0x101abaed0** have identical structural behavior: convert track+0x28 int duration to float, clamp requested time above that duration, call interval search **0x10151f340**, validate both returned indices against track keyframe arrays, then test a bitset indexed by the first keyframe. When that bit is set they call **0x1004d73a0** on the interpolation factor. The meaning of the bit is inferred as smooth/eased segment from its use; original JSON enum-to-bitset construction is not independently reconstructed here.

**0x1004d73a0** implements a rational cubic, NOT smoothstep:
```text
u = 1-t
B0 = u^3
B1 = 3*u^2*t
B2 = 3*u*t^2
B3 = t^3
ease(t) = (0.05*B1 + 1.9*B2 + B3) / (2*B0 + B1 + 2*B2 + B3)
```
Raw constants **0x1004d7468** float0.05000000074505806 and **0x1004d746c** float0.949999988079071, with explicit multiply2 for B2. Raw **0x1004d7454** FDIV returns numerator/denominator. C and ARM saved. This is the weighted rational Bezier curve with ordinates(0,.05,.95,1), weights(2,1,2,1); adopting the direct formula avoids any ambiguity from naming it cubic.

Interval search 0x10151f340 uses a cached first index and a time-bucket LUT when time moves backwards, advances while next frame time is strictly less than requested time, and returns current/next indices. Ghidra C only models the integer register part of the multi-register return, so it does not accurately reconstruct every interpolation-factor return. Upper clamp to track duration is directly observed; exact final-keyframe sentinel generation, loop/end reset and `holdLast` handling are not resolved by this small sampler alone. Do not conflate observed upper clamp with all playback lifetime behavior.

## Remaining material parity limits

The rigid transform/piece-bind order, rest*animation quaternion composition, added translation delta, shortest normalized quaternion lerp, and rational eased segments now have actual current game CPU proof. Remaining: mapping JSON interpolation-type enum to the sampler bitset; multi-track weight blending, scale/stretch inheritance and special override nodes; lifetime/holdLast/loop behavior; later body/category visibility filtering beyond matched-piece traversal. These require further reconstruction and should not be described as complete animation parity. They do not invalidate the recovered rigid pivot/offset order or empty-piece calf preservation.

## Reproduction and implementation

[map-node-block-field.py](research/avatar-071/map-node-block-field.py) reuses the SHA-bound NativeFormat decoder from [map-aot-methods.py](research/avatar-070/map-aot-methods.py) to recover NodeBlock owner and field offsets. Run with the inspected installed HytaleClient binary and Python `lief`/`capstone`. Cached Ghidra C/ARM remains local research evidence; the shipped binary, hydrated image and full proprietary decompiled functions are not committed.

The browser renderer preserves rest-position/shape-center inheritance, recomputes the rotated center when animation changes orientation, binds authored cosmetic names to animation tracks, uses the recovered NLERP and rational smooth curve, and clamps held poses. Regression fixtures cover rotation about an offset pivot, shortest-path normalization, smooth values, cosmetic track lookup and exposed body surfaces. Complete track blending/stretch/UV/category masks remain outside the reconstructed subset.
