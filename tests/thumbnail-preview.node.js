const { test } = require('node:test');
const assert = require('node:assert/strict');
global.THREE = require('../node_modules/three/build/three.cjs');
const ThumbnailRenderer = require('../assets/thumbnail-renderer');

test('category cancellation settles obsolete queued previews without interrupting the active render', async () => {
  const renderer = new ThumbnailRenderer(); renderer.initialized = true;
  let finish;
  renderer._doRender = () => new Promise(resolve => { finish = resolve; });
  const active = renderer.renderThumbnail('haircut', 'first');
  const obsolete = renderer.renderThumbnail('haircut', 'second');
  renderer.cancelPending();
  assert.equal(await obsolete, null);
  assert.equal(renderer.isRendering, true);
  finish('data:image/png;base64,active');
  assert.equal(await active, 'data:image/png;base64,active');
});

test('a stalled texture settles and allows the next thumbnail to render', async () => {
  const renderer = new ThumbnailRenderer(); renderer.initialized = true; renderer.requestTimeoutMs = 5;
  renderer.textureLoader = { load() {} };
  renderer._doRender = async (category, id) => id === 'stalled' ? await renderer._loadTexture('missing.png') : 'next-image';
  const stalled = renderer.renderThumbnail('eyes', 'stalled');
  const next = renderer.renderThumbnail('eyes', 'next');
  assert.equal(await stalled, null);
  assert.equal(await next, 'next-image');
});

test('recovered native projection preserves authored points and cape back view', () => {
  const renderer = new ThumbnailRenderer(); renderer.character = new THREE.Group();
  renderer.camera = new THREE.PerspectiveCamera(45, 92 / 149, 0.01, 100);
  renderer._applyGamePreviewPreset('cape');
  renderer.character.updateMatrixWorld(true); renderer.camera.updateMatrixWorld();
  const authored = new THREE.Vector3(5, 80, -4);
  const actual = authored.clone().multiplyScalar(0.01).applyMatrix4(renderer.character.matrixWorld).project(renderer.camera);
  // Independent native model matrix: authored /12, scale .7, yaw 202.5, then T.
  const native = authored.clone().multiplyScalar(0.7 / 12)
    .applyAxisAngle(new THREE.Vector3(0,1,0), 202.5 * Math.PI / 180)
    .add(new THREE.Vector3(-0.2, -2.8, -8));
  const lens = 1 / Math.tan(Math.PI / 8);
  assert(Math.abs(actual.x - native.x / -native.z * lens / (92 / 149)) < 1e-10);
  assert(Math.abs(actual.y - native.y / -native.z * lens) < 1e-10);
  assert.equal(renderer.character.rotation.y, 202.5 * Math.PI / 180);
  renderer._applyGamePreviewPreset('ears');
  assert.equal(renderer.character.rotation.y, -22.5 * Math.PI / 180);
  assert.throws(() => renderer._applyGamePreviewPreset('unknown'), /No game preview preset/);
});

test('placeholder textures do not suppress real variant gradients; explicit variants are honored', () => {
  const renderer = new ThumbnailRenderer();
  const data = { item: { GradientSet: 'Jean', Textures: { old: {} }, Variants: {
    Regular: { Model: 'regular', GreyscaleTexture: 'grey.png' }, Other: { Model: 'other', GreyscaleTexture: 'other.png' }
  } }, gradientSets: [{ Id: 'Jean', Gradients: { Blue: { Texture: 'blue.png', BaseColor: ['#123456'] } } }] };
  const part = renderer._resolveItemPart(data, null, 'Other');
  assert.equal(part.model, 'other'); assert.equal(part.gradientTexture, 'blue.png'); assert.equal(part.texture, undefined);
});

test('Goblin body preview uses its configured base texture without duplicating the Player model', async () => {
  const renderer = new ThumbnailRenderer(); renderer.character = new THREE.Group();
  renderer.camera = new THREE.PerspectiveCamera();
  renderer.renderer = { clear() {}, render() {}, domElement: { toDataURL: () => 'image' } };
  renderer._clearCharacter = () => {}; renderer._fitCameraToVisibleMeshes = () => {};
  renderer._renderPreviewContext = async () => {};
  renderer._fetchItemData = async () => ({ item: {
    Model: 'Characters/Player.blockymodel', GreyscaleTexture: 'Characters/Player_Textures/Player_Goblin_Greyscale.png', GradientSet: 'Skin'
  }, gradientSets: [] });
  let base, cosmeticCalls = 0;
  renderer._buildPartialBody = async (...args) => { base = args; };
  renderer._renderCosmetic = async () => { cosmeticCalls++; };
  assert.equal(await renderer._doRender('bodyCharacteristic', 'Goblin', '22', '01', 'probe'), 'image');
  assert.equal(base[0], null, 'body preview includes the full body');
  assert.equal(base[5].greyscaleTexture, 'Characters/Player_Textures/Player_Goblin_Greyscale.png');
  assert.equal(cosmeticCalls, 0);
});

test('facial preview context uses declared defaults without duplicating selected or hidden parts', async () => {
  const renderer = new ThumbnailRenderer();
  renderer.modelCache.set('default-cosmetic-catalogue', { items: {
    face: [{ id: 'Face_Neutral', isDefault: true }], eyes: [{ id: 'Medium_Eyes', isDefault: true }],
    mouth: [{ id: 'Mouth_Default', isDefault: true }], ears: [{ id: 'Default', isDefault: true }],
    headAccessory: [{ id: 'irrelevant-hat', isDefault: true }]
  } });
  const requested = [], rendered = [];
  renderer._fetchItemData = async (key, id) => { requested.push([key,id]); return { item: { Model: key, GreyscaleTexture: key + '.png' } }; };
  renderer._renderCosmetic = async (key) => { rendered.push(key); };
  await renderer._renderDefaultFacialContext('face', { hidesSkinProperties: ['Ears'] }, 0xffffff, '#fff', null);
  assert.deepEqual(rendered, ['eyes', 'mouth']);
  assert.deepEqual(requested, [['eyes', 'Medium_Eyes'], ['mouth', 'Mouth_Default']]);
});

test('queued previews capture base context and cache identity changes with the current face', async () => {
  const renderer = new ThumbnailRenderer(); renderer.initialized = true;
  const calls = [];
  renderer._doRender = async (category, id, color, tone, cacheKey, variant, context) => {
    calls.push(context); renderer.thumbnailCache.set(cacheKey, id + ':' + context.face.id); return id;
  };
  const context = { face: { id: 'Face_Neutral', colorId: null }, headAccessory: { id: 'NotBaseContext' } };
  const first = renderer.renderThumbnail('haircut', 'Morning', 'Black', '01', null, context);
  context.face.id = 'Face_Sunken';
  await first;
  await renderer.renderThumbnail('haircut', 'Morning', 'Black', '01', null, context);
  assert.equal(calls.length, 2);
  assert.equal(calls[0].face.id, 'Face_Neutral');
  assert.equal(calls[1].face.id, 'Face_Sunken');
  assert.equal(calls[0].headAccessory, undefined, 'only the recovered base-nine fields are copied');
});

test('current base context colors/variants override defaults and explicit empty optional fields stay empty', async () => {
  const renderer = new ThumbnailRenderer();
  renderer.modelCache.set('default-cosmetic-catalogue', { items: { face: [{ id: 'FallbackFace', isDefault: true }] } });
  const calls = [];
  renderer._fetchItemData = async (key, id) => {
    calls.push([key, id]); return { item: {
      Model: id, GreyscaleTexture: id + '.png', GradientSet: 'Hair',
      Variants: { Left: { Model: id + '-left', GreyscaleTexture: id + '-left.png' } }
    }, gradientSets: [{ Id: 'Hair', Gradients: { Red: { Texture: 'red.png' } } }] };
  };
  const context = renderer._snapshotPreviewContext({ haircut: 'SelectedHair.Red.Left', facialHair: null });
  const part = await renderer._resolveContextPart('haircut', context);
  assert.equal(part.model, 'SelectedHair-left'); assert.equal(part.gradientTexture, 'red.png');
  assert.equal(await renderer._resolveContextPart('facialHair', context), null);
  assert.deepEqual(calls, [['haircut', 'SelectedHair']]);
});

test('a non-body cosmetic preview retains the current Goblin base texture and skin tone', async () => {
  const renderer = new ThumbnailRenderer(); renderer.character = new THREE.Group();
  renderer.camera = new THREE.PerspectiveCamera();
  renderer.renderer = { clear() {}, render() {}, domElement: { toDataURL: () => 'image' } };
  renderer._clearCharacter = () => {}; renderer._fitCameraToVisibleMeshes = () => {};
  renderer._renderPreviewContext = async () => {}; renderer._renderCosmetic = async () => {};
  const body = { model: 'player', greyscaleTexture: 'goblin.png' };
  renderer._resolveContextPart = async () => body;
  renderer._fetchItemData = async () => ({ item: { Model: 'eyes', GreyscaleTexture: 'eyes.png' } });
  let base;
  renderer._buildPartialBody = async (...args) => { base = args; };
  await renderer._doRender('eyes', 'Medium_Eyes', null, '01', 'probe', null,
    renderer._snapshotPreviewContext({ bodyCharacteristic: { id: 'Goblin', color: '22' } }));
  assert.equal(base[5], body);
  assert.equal(base[4], 'TintGradients/Skin_Tones/22.png');
});

test('half-covering hats use the authored generic hair fallback while retaining hair color', async()=>{
 const renderer=new ThumbnailRenderer();
 renderer._resolveContextPart=async key=>key==='haircut'?{model:'long',requiresGenericHaircut:true,hairType:'Long'}:null;
 let fetched, rendered;
 renderer._fetchItemData=async (category,id)=>{fetched={category,id};return {item:{Model:'generic',Textures:{Brown:{Texture:'brown.png'}}}};};
 renderer._renderCosmetic=async (category,part)=>{rendered={category,part};};
 await renderer._renderPreviewContext('headAccessory',{headAccessoryType:'HalfCovering'},0,'#000',null,{haircut:{id:'Long',colorId:'Brown'}});
 assert.deepEqual(fetched,{category:'haircut',id:'GenericLong'});
 assert.equal(rendered.category,'haircut');assert.equal(rendered.part.model,'generic');assert.equal(rendered.part.texture,'brown.png');
});
