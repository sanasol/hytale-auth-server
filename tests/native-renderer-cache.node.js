const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const THREE = require('three');
const sharp = require('sharp');
const source = fs.readFileSync(require.resolve('../src/services/nativeRenderer'), 'utf8');
function harness() {
  let version = 1;
  const assetBuffers = new Map();
  let extracts = 0;
  const context = { THREE, sharp, Uint8Array, Buffer, process: { env: {} }, console, global: {}, setTimeout, clearTimeout, module: { exports: {} }, require(name) {
    if (name === '../config') return { assetsPath: '/fixture.zip' };
    if (name === 'fs') return { statSync: () => ({ size: 100, mtimeMs: version }) };
    if (name === './assets') return { extractAsset: path => { extracts++; return assetBuffers.get(path); } };
    return {};
  } };
  vm.createContext(context);
  vm.runInContext(source.replace('let THREE = null;', 'let THREE = globalThis.THREE;').replace('let sharp = null;', 'let sharp = globalThis.sharp;') + '\nmodule.exports.probes={ createDataTexture, createTintedTexture, createBoxMesh, createQuadMesh, renderCosmeticNode, setMaterialState, prepareTransparency, queueRender, acquireRenderer, textureCache, modelCache, materialCache, geometryCache, boundedSet, setRenderer: value=>sharedRenderer=value };', context);
  return { probes: context.module.exports.probes, assetBuffers, bump: () => version++, extracts: () => extracts };
}

test('raw RGBA texture follows browser atlas orientation and avoids implicit color conversion', () => {
  const h = harness();
  const texture = h.probes.createDataTexture(Uint8Array.from([8, 18, 28, 128]), 1, 1);
  assert.equal(texture.isDataTexture, true);
  assert.equal(texture.flipY, true);
  assert.equal(texture.colorSpace, THREE.NoColorSpace);
  assert.deepEqual([...texture.image.data], [8, 18, 28, 128]);
});

test('tint cache preserves nongreyscale pixels and alpha, exact gradient lookup, and reuses decoded textures', async () => {
  const h = harness();
  h.assetBuffers.set('Common/grey.png', await sharp(Buffer.from([64,64,64,128, 20,30,40,255]), { raw: { width: 2, height: 1, channels: 4 } }).png().toBuffer());
  const gradient = Buffer.alloc(256*4); for(let i=0;i<256;i++) { gradient[i*4] = i; gradient[i*4+1] = 255-i; gradient[i*4+2] = 37; gradient[i*4+3] = 255; }
  h.assetBuffers.set('Common/gradient.png', await sharp(gradient, { raw: { width: 256, height: 1, channels: 4 } }).png().toBuffer());
  const first = await h.probes.createTintedTexture('grey.png', '#ffffff', 'gradient.png');
  assert.deepEqual([...first.image.data], [64,191,37,128, 20,30,40,255]);
  assert.equal(await h.probes.createTintedTexture('grey.png', '#ffffff', 'gradient.png'), first);
  assert.equal(h.extracts(), 2);
});

test('render queue serializes asynchronous work and recovers after failure', async () => {
  const h = harness(); const events = [];
  const first = h.probes.queueRender(async()=> { events.push('first:start'); await new Promise(r=>setTimeout(r,2)); events.push('first:end'); throw new Error('fixture failure'); });
  const second = h.probes.queueRender(async()=> { events.push('second'); return 42; });
  await assert.rejects(first, /fixture failure/);
  assert.equal(await second, 42);
  assert.deepEqual(events, ['first:start','first:end','second']);
});

test('asset version change disposes bounded resources before next job', async () => {
  const h = harness(); let disposed = 0;
  await h.probes.queueRender(async()=>{});
  h.probes.textureCache.set('fixture', { dispose: () => disposed++ }); h.probes.modelCache.set('fixture', {});
  h.bump(); await h.probes.queueRender(async()=>{});
  assert.equal(disposed, 1); assert.equal(h.probes.textureCache.size, 0); assert.equal(h.probes.modelCache.size, 0);
});

test('cache eviction disposes obsolete GPU objects', () => {
  const h = harness(); const cache = new Map(); let disposed = 0;
  h.probes.boundedSet(cache, 'old', { dispose: () => disposed++ }, 1, true);
  h.probes.boundedSet(cache, 'new', { dispose: () => {} }, 1, true);
  assert.equal(disposed, 1); assert.equal(cache.size, 1); assert.ok(cache.has('new'));
});

test('dimension changes resize the existing context rather than allocating another', () => {
  const h = harness(); const sizes = [];
  const shared = { mockCanvas: { width: 128, height: 128 }, renderer: { setSize: (w,h)=>sizes.push(['renderer',w,h]) }, glContext: { getExtension: name => name === 'STACKGL_resize_drawingbuffer' ? { resize: (w,h)=>sizes.push(['context',w,h]) } : null } };
  h.probes.setRenderer(shared);
  assert.equal(h.probes.acquireRenderer(256,128), shared);
  assert.deepEqual(sizes, [['context',256,128],['renderer',256,128]]);
  assert.throws(()=>h.probes.acquireRenderer(100000,1), /dimensions/);
});

test('piece root contributes no own geometry and reparents its child directly to reference bone', () => {
  const h = harness(); const character = new THREE.Group(); const bone = new THREE.Group(); bone.name='Head'; character.add(bone);
  const shape = { type:'box', settings:{ isPiece:true, size:{x:4,y:4,z:4} } };
  h.probes.renderCosmeticNode({name:'Head', position:{x:100,y:100,z:100}, orientation:{x:0,y:1,z:0,w:0}, shape, children:[{name:'Child', position:{x:2,y:0,z:0}, shape:{type:'none'}}]}, character, character, 0xffffff);
  assert.equal(bone.children.length,1);
  assert.equal(bone.children[0].name,'Child_cosmetic');
  assert.equal(bone.children[0].children.length,0, 'root box must never be emitted');
  assert.equal(bone.children[0].position.x,0.02);
});

test('textured boxes discard zero alpha without dropping faint translucent texels', () => {
  const h=harness(); const texture=h.probes.createDataTexture(Uint8Array.from([20,30,40,1]),1,1);
  const mesh=h.probes.createBoxMesh({settings:{size:{x:1,y:1,z:1}}},0xffffff,texture,'Chest');
  assert.equal(mesh.material.alphaTest,1/255);
});

test('per-mesh polygon offsets cannot poison a shared material or the next unclothed render', () => {
  const h = harness(); const texture = h.probes.createDataTexture(Uint8Array.from([60,70,80,255]),1,1);
  const shape={settings:{size:{x:1,y:1,z:1}}};
  const head=h.probes.createBoxMesh(shape,0xffffff,texture,'Head');
  const leg=h.probes.createBoxMesh(shape,0xffffff,texture,'L-Calf');
  assert.equal(head.material,leg.material, 'immutable base state may be shared');
  h.probes.setMaterialState(leg,{polygonOffset:true,polygonOffsetFactor:1,polygonOffsetUnits:1});
  assert.notEqual(head.material,leg.material);
  assert.equal(head.material.polygonOffset,false);
  assert.equal(leg.material.polygonOffset,true);
  const nextUnclothed=h.probes.createBoxMesh(shape,0xffffff,texture,'L-Calf');
  assert.equal(nextUnclothed.material,head.material);
  assert.equal(nextUnclothed.material.polygonOffset,false);
});

test('native transparency preparation never disables facial depth or resets render order', () => {
  const h=harness(); const texture=h.probes.createDataTexture(Uint8Array.from([60,70,80,255]),1,1);
  const eye=h.probes.createQuadMesh({settings:{size:{x:1,y:1},normal:'+Z'}},0xffffff,texture,'L-Eye');
  const hair=h.probes.createBoxMesh({settings:{size:{x:1,y:1,z:1}}},0xffffff,texture,'Hair');
  eye.renderOrder=17; const scene=new THREE.Scene();scene.add(eye,hair);
  assert.equal(h.probes.prepareTransparency(scene),2);
  assert.equal(eye.material.depthWrite,true);
  assert.equal(hair.material.depthWrite,true);
  assert.equal(eye.renderOrder,17);
  assert.equal(eye.material.userData.nativeParameters.depthWrite,true);
});
