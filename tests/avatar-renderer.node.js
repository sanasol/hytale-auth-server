const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const THREE = require('three');
global.THREE = THREE;
const Avatar = require('../assets/avatar');
const Thumbnail = require('../assets/thumbnail-renderer');
const head = require('./renderer-fixtures/player-head-070.json');
const texture = new THREE.Texture();
texture.image = { width: 128, height: 128 };
const source = fs.readFileSync(require.resolve('../src/services/nativeRenderer'), 'utf8');
const nativeAssets = {};
const nativeContext = { require: (name) => name === './assets' ? nativeAssets : ({}), module: { exports: {} }, global: {}, console, setTimeout, clearTimeout, THREE };
vm.createContext(nativeContext);
vm.runInContext(source.replace('let THREE = null;', 'let THREE = globalThis.THREE;') + '\nmodule.exports.probes = { createBoxMesh, renderCosmeticNode, applyTransform, buildCharacter };', nativeContext);
const native = nativeContext.module.exports.probes;

for (const [name, createBox] of [
  ['browser', (shape) => Avatar.prototype._createBoxMesh.call({ wireframeMode: false }, shape, 0xffffff, 'Head', texture)],
  ['thumbnail', (shape) => Thumbnail.prototype._createBoxMesh.call(Object.create(Thumbnail.prototype), shape, 0xffffff, 'Head', texture)],
  ['native', (shape) => native.createBoxMesh(shape, 0xffffff, texture, 'Head')],
]) {
  test(`${name}: fresh 0.7 head has five faces, no default-UV front`, () => {
    const mesh = createBox(head.shape);
    assert.equal(mesh.geometry.index.count, 30);
    const indices = [...mesh.geometry.index.array];
    assert.ok(indices.every(i => i < 16 || i >= 20), 'front vertices must not enter draw indices');
    assert.equal(mesh.geometry.getAttribute('position').count, 24, 'preserve face UV vertex numbering');
  });
}

test('shape center offsets remain inherited as required by blockymodel format', () => {
  const group = new THREE.Group();
  Avatar.prototype._applyTransform(group, head);
  assert.ok(Math.abs(group.position.y - 0.28) < 1e-9);
  assert.ok(Math.abs(group.position.z - 0.01) < 1e-9);
});

for (const [name, render] of [
  ['browser', (viewer, node, parent) => Avatar.prototype._renderCosmeticNode.call(viewer, node, parent, 0xffffff, 'haircut', null, 0)],
  ['thumbnail', (viewer, node, parent) => Thumbnail.prototype._renderCosmeticNode.call(viewer, node, parent, 0xffffff, 'haircut', null, 0, 0)],
  ['native', (viewer, node, parent) => native.renderCosmeticNode(node, parent, viewer.character, 0xffffff, null, 'haircut')],
]) {
  test(`${name}: same-name cosmetic is only bound when declared isPiece`, async () => {
    const proto = name === 'thumbnail' ? Thumbnail.prototype : Avatar.prototype;
    const viewer = Object.create(proto);
    viewer.character = new THREE.Group();
    const bone = new THREE.Group(); bone.name = 'Head'; viewer.character.add(bone);
    const parent = new THREE.Group(); viewer.character.add(parent);
    const node = { name: 'Head', position: { x: 8, y: 4, z: 0 }, orientation: { x: 0, y: 1, z: 0, w: 0 }, shape: { type: 'none', settings: { isPiece: false } } };
    await render(viewer, node, parent);
    assert.equal(bone.children.length, 0);
    assert.equal(parent.children.length, 1);
    node.shape.settings.isPiece = true;
    await render(viewer, node, parent);
    assert.equal(bone.children.length, 1);
    assert.equal(bone.children[0].quaternion.w, 1, 'bound node inherits reference orientation, not template orientation');
  });
}

test('rebuilding character disposes geometry/material/generated textures while retaining reusable textures', () => {
  const viewer = Object.create(Avatar.prototype);
  const cached = new THREE.Texture(); const generated = new THREE.Texture();
  viewer.character = new THREE.Group(); viewer.textureCache = new Map([['cached', cached]]);
  viewer.originalTransforms = new Map([['Head', {}]]); viewer.hiddenBodyParts = new Set(['Chest']); viewer.polygonOffsetParts = new Set(['Arm']);
  let cachedDisposed = false, generatedDisposed = false, geometryDisposed = false, materialDisposed = false;
  cached.addEventListener('dispose', () => cachedDisposed = true); generated.addEventListener('dispose', () => generatedDisposed = true);
  const geometry = new THREE.BoxGeometry(); geometry.addEventListener('dispose', () => geometryDisposed = true);
  const material = new THREE.MeshBasicMaterial({ map: generated }); material.addEventListener('dispose', () => materialDisposed = true);
  viewer.character.add(new THREE.Mesh(geometry, material));
  viewer._clearCharacter();
  assert.equal(viewer.character.children.length, 0);
  assert.equal(generatedDisposed, true); assert.equal(cachedDisposed, false);
  assert.equal(geometryDisposed, true); assert.equal(materialDisposed, true);
  assert.equal(viewer.originalTransforms.size, 0); assert.equal(viewer.hiddenBodyParts.size, 0);
});

test('destroyed viewers never schedule another frame', () => {
  const viewer = Object.create(Avatar.prototype); viewer._destroyed = true;
  global.requestAnimationFrame = () => { throw new Error('animation must have stopped'); };
  viewer._animate();
  delete global.requestAnimationFrame;
});

const calf = require('./renderer-fixtures/player-calf-070.json');

test('shorts retain exposed calf geometry in browser preview', () => {
  const viewer = Object.create(Avatar.prototype);
  viewer.character = new THREE.Group(); viewer.hiddenBodyParts = new Set(); viewer.polygonOffsetParts = new Set();
  viewer._determineHiddenParts({ parts: { pants: { model: 'Cosmetics/Pants/Shorty_Slim.blockymodel' } } });
  viewer._renderPlayerNode(calf, viewer.character, 0xffffff, texture);
  const mesh = viewer.character.getObjectByName('L-Calf').children.find(o => o.isMesh);
  assert.ok(mesh, 'shorts have no replacement calf mesh, so base calf must remain');
  assert.equal(mesh.material.polygonOffset, true);
});

test('shorts retain exposed calf geometry in native rendering', async () => {
  nativeAssets.extractAsset = (name) => Buffer.from(JSON.stringify({ nodes: name.includes('Player.blockymodel') ? [calf] : [{ name: 'L-Calf', shape: { type: 'none', settings: { isPiece: true } } }] }));
  nativeContext.fixtureTexture = texture;
  vm.runInContext('createTintedTexture = async () => fixtureTexture;', nativeContext);
  const character = new THREE.Group();
  await native.buildCharacter({ skinTone: '01', parts: { pants: { model: 'Cosmetics/Pants/Shorty_Slim.blockymodel' } } }, character);
  const mesh = character.getObjectByName('L-Calf').children.find(o => o.isMesh);
  assert.ok(mesh);
  assert.equal(mesh.material.polygonOffset, true);
});

test('thumbnail pants visibility retains exposed base calves', async () => {
  const context = { THREE, module: { exports: {} }, console };
  vm.createContext(context);
  vm.runInContext(fs.readFileSync(require.resolve('../assets/thumbnail-renderer'), 'utf8') + '\nmodule.exports.hiddenParts = HIDE_PARTS_FOR_COSMETIC;', context);
  const hideParts = new Set(context.module.exports.hiddenParts.pants);
  const renderer = Object.create(Thumbnail.prototype); renderer.character = new THREE.Group();
  await renderer._renderPartialPlayerNode(calf, renderer.character, 0xffffff, texture, new Set(['L-Calf']), hideParts, 0);
  const mesh = renderer.character.getObjectByName('L-Calf').children.find(o => o.isMesh);
  assert.equal(mesh.visible, true);
  assert.equal(mesh.material.polygonOffset, true);
});

const faceAssembly = require('./renderer-fixtures/face-assembly-070.json');
test('model-authored face is flush with head; eyes keep their authored 0.1px layer gap', async () => {
  const viewer = Object.create(Avatar.prototype);
  viewer.character = new THREE.Group(); viewer.hiddenBodyParts = new Set(); viewer.polygonOffsetParts = new Set();
  await viewer._renderPlayerModel(faceAssembly.player, viewer.character, 0xffffff, texture);
  await viewer._renderCosmeticModel(faceAssembly.face, viewer.character, 0xffffff, 'face', texture, 0);
  await viewer._renderCosmeticModel(faceAssembly.eyes, viewer.character, 0xffffff, 'eyes', texture, 0);
  viewer.character.updateMatrixWorld(true);
  const center = name => viewer._findBoneByName(viewer.character, name).children.find(n => n.isMesh).getWorldPosition(new THREE.Vector3());
  const headCenter = center('Head'); const faceCenter = center('Face_cosmetic');
  assert.ok(Math.abs(faceCenter.z - (headCenter.z + 14 * 0.01)) < 1e-8);
  assert.ok(Math.abs(center('R-Eye_cosmetic').z - center('R-Eye-Background_cosmetic').z - 0.001) < 1e-8);
});

test('eye whites and pupil use the original texture alpha without a generated shadow', async () => {
  const viewer = Object.create(Avatar.prototype);
  viewer.character = new THREE.Group();
  viewer.onLoadProgress = () => {};
  viewer.wireframeMode = false;
  viewer._fetchModel = async path => ({nodes: path.includes('Player.blockymodel') ? [] : faceAssembly.eyes});
  viewer._createTintedTexture = async () => texture;
  await viewer._buildCharacter({skinTone:'01', parts:{eyes:{model:'Eyes.blockymodel', greyscaleTexture:'Eyes.png'}}});
  const background = viewer._findBoneByName(viewer.character, 'R-Eye-Background_cosmetic').children.find(n => n.isMesh);
  const pupil = viewer._findBoneByName(viewer.character, 'R-Eye_cosmetic').children.find(n => n.isMesh);
  assert.equal(background.material.map, texture);
  assert.equal(pupil.material.map, texture);
  assert.equal(background.material.alphaTest, 1 / 255);
  assert.equal(background.material.transparent, true);
});
