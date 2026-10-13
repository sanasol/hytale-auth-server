/**
 * Native Server-Side Renderer for Hytale Avatars
 * Uses headless-gl (gl) + Three.js + sharp for GPU-free rendering
 */

const path = require('path');
const fs = require('fs');
const config = require('../config');
const assets = require('./assets');
const storage = require('./storage');

// Polyfill browser globals for Three.js in Node.js
if (typeof global.requestAnimationFrame === 'undefined') {
  global.requestAnimationFrame = (cb) => setTimeout(cb, 16);
  global.cancelAnimationFrame = (id) => clearTimeout(id);
}
if (typeof global.self === 'undefined') {
  global.self = global;
}

// Lazy-load optional dependencies
let THREE = null;
let createContext = null;
let sharp = null;
let rendererAvailable = false;
let initError = null;


// Bounded caches belong to one serialized GL renderer, not to individual players.
const DEBUG_RENDER = process.env.AVATAR_RENDER_DEBUG === '1';
const textureCache = new Map();
const modelCache = new Map();
const materialCache = new Map();
const geometryCache = new Map();
let assetVersion = null;
let sharedRenderer = null;
let renderTail = Promise.resolve();
let queuedRenders = 0;
let contextsCreated = 0;

function cachedGet(cache, key) {
  const value = cache.get(key);
  cache.delete(key);
  cache.set(key, value);
  return value;
}

function boundedSet(cache, key, value, limit, dispose = false) {
  cache.set(key, value);
  while (cache.size > limit || (cache === textureCache && [...cache.values()].reduce((bytes, texture) => bytes + (texture.image?.data?.byteLength || 0), 0) > 32 * 1024 * 1024)) {
    const oldest = cache.keys().next().value;
    if (dispose) cache.get(oldest)?.dispose();
    cache.delete(oldest);
  }
  return value;
}

function resetRenderResources() {
  for (const cache of [materialCache, geometryCache, textureCache]) {
    for (const value of cache.values()) value.dispose();
    cache.clear();
  }
  modelCache.clear();
  if (sharedRenderer) {
    sharedRenderer.renderer.dispose();
    sharedRenderer.glContext.getExtension('STACKGL_destroy_context')?.destroy();
    sharedRenderer = null;
  }
}

function checkAssetVersion() {
  const stat = fs.statSync(config.assetsPath);
  const version = `${stat.size}:${stat.mtimeMs}`;
  if (assetVersion !== version) {
    resetRenderResources();
    assetVersion = version;
  }
}

function queueRender(action) {
  if (queuedRenders >= 64) return Promise.reject(new Error('Avatar render queue is full'));
  queuedRenders++;
  const result = renderTail.then(async () => {
    try { checkAssetVersion(); return await action(); }
    catch (error) { resetRenderResources(); throw error; }
    finally { queuedRenders--; }
  });
  renderTail = result.catch(() => {});
  return result;
}

function acquireRenderer(width, height) {
  if (!Number.isInteger(width) || !Number.isInteger(height) || width < 1 || height < 1 || width > 1024 || height > 1024) {
    throw new Error('Avatar render dimensions must be between 1 and 1024');
  }
  if (!sharedRenderer) sharedRenderer = createRenderer(width, height);
  else if (sharedRenderer.mockCanvas.width !== width || sharedRenderer.mockCanvas.height !== height) {
    const resize = sharedRenderer.glContext.getExtension('STACKGL_resize_drawingbuffer');
    if (!resize) {
      resetRenderResources();
      sharedRenderer = createRenderer(width, height);
    } else {
      resize.resize(width, height);
      sharedRenderer.renderer.setSize(width, height);
    }
  }
  return sharedRenderer;
}

function extractCommonAsset(assetPath) {
  return assets.extractAsset(assetPath.startsWith('Common/') ? assetPath : `Common/${assetPath}`);
}

function loadModel(assetPath) {
  if (modelCache.has(assetPath)) return cachedGet(modelCache, assetPath);
  const buffer = extractCommonAsset(assetPath);
  if (!buffer) return null;
  return boundedSet(modelCache, assetPath, JSON.parse(buffer.toString()), 256);
}

function createDataTexture(data, width, height) {
  const texture = new THREE.DataTexture(new Uint8Array(data), width, height, THREE.RGBAFormat);
  // Browser r128 uses unencoded atlas pixels and LinearEncoding output.
  // Do not introduce an additional sRGB decode/encode on this path.
  texture.flipY = true;
  texture.magFilter = THREE.NearestFilter;
  texture.minFilter = THREE.NearestFilter;
  texture.wrapS = THREE.ClampToEdgeWrapping;
  texture.wrapT = THREE.ClampToEdgeWrapping;
  texture.generateMipmaps = false;
  texture.userData = { width, height };
  texture.needsUpdate = true;
  return texture;
}

function createCachedMaterial(parameters) {
  const key = JSON.stringify({ ...parameters, map: parameters.map?.uuid });
  if (materialCache.has(key)) return cachedGet(materialCache, key);
  const material = new THREE.MeshLambertMaterial(parameters);
  material.userData.nativeParameters = Object.freeze({ ...parameters });
  return boundedSet(materialCache, key, material, 1024, true);
}

function setMaterialState(mesh, overrides) {
  // Cached materials are immutable templates; render-state variants have their own keys.
  const parameters = mesh.material.userData.nativeParameters;
  mesh.material = createCachedMaterial({ ...parameters, ...overrides });
}

function reuseGeometry(geometry, shape, texture) {
  const key = JSON.stringify([geometry.type, shape, texture?.userData?.width, texture?.userData?.height]);
  if (geometryCache.has(key)) {
    geometry.dispose();
    return cachedGet(geometryCache, key);
  }
  geometry.userData.nativeCached = true;
  return boundedSet(geometryCache, key, geometry, 2048, true);
}

function disposeScene(scene) {
  scene.traverse(object => {
    if (object.isMesh && !object.geometry.userData.nativeCached) object.geometry.dispose();
  });
  scene.clear();
}

const SCALE = 0.01; // BlockyModel units to world units

// Skin tone color mapping
const SKIN_TONES = {
  '01': 0xf4c39a, '02': 0xf5c490, '03': 0xe0ae72, '04': 0xba7f5b,
  '05': 0x945d44, '06': 0x6f3b2c, '07': 0x4f2a24, '08': 0xdcc7a8,
  '09': 0xf5bc83, '10': 0xd98c5b, '11': 0xab7a4c, '12': 0x7d432b,
  '13': 0x513425, '14': 0x31221f, '15': 0xd5a082, '16': 0x63492f,
  '17': 0x5e3a2f, '18': 0x4d272b, '19': 0x8aacfb, '20': 0xa78af1,
  '21': 0xfc8572, '22': 0x9bc55d, '25': 0x4354e6, '26': 0x6c2abd,
  '27': 0x765e48, '28': 0xf3f3f3, '29': 0x998d71, '30': 0x50843a,
  '31': 0xb22a2a, '32': 0x3276c3, '33': 0x092029, '35': 0x5eae37,
  '36': 0xff72c2, '37': 0xf4c944, '38': 0x6c3f40, '39': 0xff9c5b,
  '41': 0xff95cd, '42': 0xa0dfff, '45': 0xd5f0a0, '46': 0xddbfe8,
  '47': 0xf0b9f2, '48': 0xdcc5b0, '49': 0xec6ff7, '50': 0x2b2b2f,
  '51': 0xf06f47, '52': 0x131111
};

// Default colors for cosmetic types
const DEFAULT_COLORS = {
  'haircut': 0x4a3728, 'facialHair': 0x4a3728, 'eyebrows': 0x4a3728,
  'pants': 0x2c3e50, 'overpants': 0x34495e,
  'undertop': 0x5dade2, 'overtop': 0x2980b9,
  'shoes': 0x1a1a1a, 'gloves': 0x8b4513,
  'mouth': 0xc0392b, 'eyes': 0x3498db, 'underwear': 0xecf0f1,
  'cape': 0x8e44ad, 'headAccessory': 0xf1c40f,
  'faceAccessory': 0xbdc3c7, 'earAccessory': 0xf1c40f
};

/**
 * Initialize the native renderer dependencies
 */
function init() {
  if (THREE !== null) return rendererAvailable;

  try {
    THREE = require('three');
    createContext = require('gl');
    sharp = require('sharp');

    rendererAvailable = true;
    console.log('[NativeRenderer] Dependencies loaded successfully');
  } catch (err) {
    initError = err;
    rendererAvailable = false;
    console.warn('[NativeRenderer] Dependencies not available:', err.message);
    console.warn('[NativeRenderer] Install with: npm install three gl sharp');
  }

  return rendererAvailable;
}

/**
 * Check if native rendering is available
 */
function isAvailable() {
  if (THREE === null) init();
  return rendererAvailable;
}

/**
 * Get initialization error if any
 */
function getInitError() {
  return initError;
}

/**
 * Create a WebGL context and Three.js renderer
 */
function createRenderer(width, height) {
  if (!isAvailable()) {
    throw new Error('Native renderer not available: ' + (initError?.message || 'unknown'));
  }

  // Enable consistent color math across environments
  if (THREE.ColorManagement) {
    THREE.ColorManagement.enabled = false;
  }

  // Create headless WebGL context with high precision and alpha support
  contextsCreated++;
  const glContext = createContext(width, height, {
    preserveDrawingBuffer: true,
    antialias: true,
    alpha: true,           // Enable alpha channel for transparency
    depth: true,           // Enable depth buffer for proper z-sorting
    stencil: false,        // Not needed
    precision: 'highp'     // Explicitly request high precision
  });

  if (!glContext) {
    throw new Error('Failed to create WebGL context');
  }

  // Mock canvas for Three.js
  const mockCanvas = {
    width,
    height,
    style: {},
    addEventListener: () => {},
    removeEventListener: () => {},
    getContext: () => glContext,
    clientWidth: width,
    clientHeight: height
  };

  // Create Three.js renderer
  const renderer = new THREE.WebGLRenderer({
    canvas: mockCanvas,
    context: glContext,
    antialias: true,
    alpha: true,
    preserveDrawingBuffer: true,
    premultipliedAlpha: false  // Match browser alpha handling
  });

  renderer.setSize(width, height);

  // FIX P2: Match browser Three.js default settings
  renderer.useLegacyLights = true;
  renderer.toneMapping = THREE.NoToneMapping;
  renderer.toneMappingExposure = 1.0;

  // Match the current browser r128 linear output; pixels receive no extra gamma pass.
  if (THREE.SRGBColorSpace) {
    renderer.outputColorSpace = THREE.LinearSRGBColorSpace;
  } else if (renderer.outputEncoding !== undefined) {
    renderer.outputEncoding = THREE.LinearEncoding;
  }

  return { renderer, glContext, mockCanvas };
}

/**
 * Load texture from asset buffer into Three.js
 */
async function loadTextureFromAsset(texturePath) {
  if (!texturePath) return null;
  const key = `raw:${texturePath}`;
  if (textureCache.has(key)) return cachedGet(textureCache, key);
  try {
    const buffer = extractCommonAsset(texturePath);
    if (!buffer) return null;
    const { data, info } = await sharp(buffer).ensureAlpha().raw().toBuffer({ resolveWithObject: true });
    return boundedSet(textureCache, key, createDataTexture(data, info.width, info.height), 1024, true);
  } catch (error) {
    console.error(`[NativeRenderer] Cannot load texture ${texturePath}:`, error.message);
    return null;
  }
}

/**
 * Create a tinted texture from greyscale
 * FIX P0: Normalize gradient path with Common/ prefix
 * FIX P2: Remove * 2 multiplier that caused color overexposure
 */
async function createTintedTexture(greyscalePath, baseColor, gradientPath = null) {
  const key = JSON.stringify(['tinted', greyscalePath, baseColor, gradientPath]);
  if (textureCache.has(key)) return cachedGet(textureCache, key);
  const original = await loadTextureFromAsset(greyscalePath);
  if (!original) return null;
  const data = new Uint8Array(original.image.data);
  const gradient = gradientPath ? await loadTextureFromAsset(gradientPath) : null;
  const color = parseColor(baseColor);
  for (let i = 0; i < data.length; i += 4) {
    if (!data[i + 3] || data[i] !== data[i + 1] || data[i + 1] !== data[i + 2]) continue;
    const grey = data[i];
    if (gradient) {
      const index = Math.min(grey, gradient.image.width - 1) * 4;
      data[i] = gradient.image.data[index];
      data[i + 1] = gradient.image.data[index + 1];
      data[i + 2] = gradient.image.data[index + 2];
    } else {
      data[i] = Math.round(color.r * grey / 255);
      data[i + 1] = Math.round(color.g * grey / 255);
      data[i + 2] = Math.round(color.b * grey / 255);
    }
  }
  return boundedSet(textureCache, key, createDataTexture(data, original.image.width, original.image.height), 1024, true);
}

/**
 * Create eye shadow texture from original eye texture
 * Creates a semi-transparent shadow at the top of eye backgrounds
 */


/**
 * Parse color to RGB object
 */
function parseColor(color) {
  if (typeof color === 'number') {
    return {
      r: (color >> 16) & 255,
      g: (color >> 8) & 255,
      b: color & 255
    };
  }
  if (typeof color === 'string') {
    if (color.startsWith('#')) {
      const hex = color.slice(1);
      return {
        r: parseInt(hex.substr(0, 2), 16),
        g: parseInt(hex.substr(2, 2), 16),
        b: parseInt(hex.substr(4, 2), 16)
      };
    }
  }
  if (Array.isArray(color)) {
    return parseColor(color[0]);
  }
  return { r: 200, g: 200, b: 200 };
}

/**
 * Get skin tone color
 */
function getSkinToneColor(tone) {
  return SKIN_TONES[tone] || SKIN_TONES['01'];
}

/**
 * Get skin tone gradient path
 */
function getSkinToneGradientPath(tone) {
  const validTones = Object.keys(SKIN_TONES);
  if (validTones.includes(tone)) {
    return `TintGradients/Skin_Tones/${tone}.png`;
  }
  return 'TintGradients/Skin_Tones/01.png';
}

/**
 * Create a box mesh from shape data
 * FIX P2: Use MeshLambertMaterial for browser parity (Gouraud shading matches browser)
 * FIX P1: depthWrite=true for all meshes to maintain proper depth sorting
 */
function createBoxMesh(shape, color, texture = null, nodeName = '') {
  const settings = shape.settings;
  if (!settings || !settings.size) return null;

  const stretch = shape.stretch || { x: 1, y: 1, z: 1 };
  const sx = Math.abs(stretch.x || 1);
  const sy = Math.abs(stretch.y || 1);
  const sz = Math.abs(stretch.z || 1);

  const flipX = (stretch.x || 1) < 0;
  const flipY = (stretch.y || 1) < 0;
  const flipZ = (stretch.z || 1) < 0;

  const width = settings.size.x * sx * SCALE;
  const height = settings.size.y * sy * SCALE;
  const depth = settings.size.z * sz * SCALE;

  const geometry = new THREE.BoxGeometry(width, height, depth);

  // An omitted texture face is absent geometry (e.g. Player Head front).
  // Leaving Three.js default UVs here samples the entire body atlas.
  if (shape.textureLayout && Object.keys(shape.textureLayout).length > 0) {
    const faces = ['right', 'left', 'top', 'bottom', 'front', 'back'];
    const indices = Array.from(geometry.index.array);
    geometry.setIndex(faces.flatMap((face, i) =>
      shape.textureLayout[face] ? indices.slice(i * 6, i * 6 + 6) : []
    ));
    geometry.clearGroups();
  }

  // Apply UV mapping if texture layout provided
  if (texture && shape.textureLayout) {
    applyBoxUVs(geometry, shape, texture);
  }

  // MeshLambertMaterial for proper lighting interaction
  let material;
  if (texture) {
    material = createCachedMaterial({
      map: texture,
      color: 0xffffff,
      alphaTest: 1 / 255,
      transparent: true,
      side: THREE.DoubleSide,
      depthWrite: true
    });
  } else {
    material = createCachedMaterial({
      color: color,
      side: THREE.DoubleSide
    });
  }

  const mesh = new THREE.Mesh(reuseGeometry(geometry, shape, texture), material);

  if (flipX) mesh.scale.x = -1;
  if (flipY) mesh.scale.y = -1;
  if (flipZ) mesh.scale.z = -1;

  return mesh;
}

/**
 * Apply UV mapping to box geometry
 * FIX: Added angle rotation and mirror support to match browser version
 */
function applyBoxUVs(geometry, shape, texture) {
  const texW = texture.userData?.width || 64;
  const texH = texture.userData?.height || 64;
  const settings = shape.settings;

  const pixelW = settings.size.x;
  const pixelH = settings.size.y;
  const pixelD = settings.size.z;

  const faceMap = ['right', 'left', 'top', 'bottom', 'front', 'back'];
  const uvAttr = geometry.getAttribute('uv');
  const uvArray = uvAttr.array;

  for (let faceIdx = 0; faceIdx < 6; faceIdx++) {
    const faceName = faceMap[faceIdx];
    const layout = shape.textureLayout[faceName];

    if (layout && layout.offset) {
      const angle = layout.angle || 0;

      // Calculate base UV size based on face orientation
      let uv_size = [0, 0];
      if (faceName === 'left' || faceName === 'right') {
        uv_size = [pixelD, pixelH];
      } else if (faceName === 'top' || faceName === 'bottom') {
        uv_size = [pixelW, pixelD];
      } else {
        uv_size = [pixelW, pixelH];
      }

      // Handle mirror (matching browser logic)
      let uv_mirror = [
        layout.mirror?.x ? -1 : 1,
        layout.mirror?.y ? -1 : 1
      ];

      const uv_offset = [layout.offset.x, layout.offset.y];

      // Calculate UV coordinates based on angle (matching browser/Blockbench plugin logic)
      let result;
      switch (angle) {
        case 90:
          // Swap size and mirror, flip mirror X
          [uv_size[0], uv_size[1]] = [uv_size[1], uv_size[0]];
          [uv_mirror[0], uv_mirror[1]] = [uv_mirror[1], uv_mirror[0]];
          uv_mirror[0] *= -1;
          result = [
            uv_offset[0],
            uv_offset[1] + uv_size[1] * uv_mirror[1],
            uv_offset[0] + uv_size[0] * uv_mirror[0],
            uv_offset[1]
          ];
          break;
        case 180:
          // Flip both mirrors
          uv_mirror[0] *= -1;
          uv_mirror[1] *= -1;
          result = [
            uv_offset[0] + uv_size[0] * uv_mirror[0],
            uv_offset[1] + uv_size[1] * uv_mirror[1],
            uv_offset[0],
            uv_offset[1]
          ];
          break;
        case 270:
          // Swap size and mirror, flip mirror Y
          [uv_size[0], uv_size[1]] = [uv_size[1], uv_size[0]];
          [uv_mirror[0], uv_mirror[1]] = [uv_mirror[1], uv_mirror[0]];
          uv_mirror[1] *= -1;
          result = [
            uv_offset[0] + uv_size[0] * uv_mirror[0],
            uv_offset[1],
            uv_offset[0],
            uv_offset[1] + uv_size[1] * uv_mirror[1]
          ];
          break;
        default: // 0 degrees
          result = [
            uv_offset[0],
            uv_offset[1],
            uv_offset[0] + uv_size[0] * uv_mirror[0],
            uv_offset[1] + uv_size[1] * uv_mirror[1]
          ];
          break;
      }

      // Convert pixel coordinates to normalized UV (0-1) with Y flip for WebGL
      const u1 = result[0] / texW;
      const v1 = 1.0 - result[1] / texH;
      const u2 = result[2] / texW;
      const v2 = 1.0 - result[3] / texH;

      // FIX P2: Validate UV coordinates - NaN check
      if (isNaN(u1) || isNaN(v1) || isNaN(u2) || isNaN(v2)) {
        console.warn(`[NativeRenderer] Invalid UV coordinates for face ${faceName}, using defaults`);
        continue;
      }

      const baseIdx = faceIdx * 4 * 2;

      // Apply the rotation to the UV assignment based on angle (matching browser)
      if (angle === 90) {
        uvArray[baseIdx + 0] = u1; uvArray[baseIdx + 1] = v2;
        uvArray[baseIdx + 2] = u1; uvArray[baseIdx + 3] = v1;
        uvArray[baseIdx + 4] = u2; uvArray[baseIdx + 5] = v2;
        uvArray[baseIdx + 6] = u2; uvArray[baseIdx + 7] = v1;
      } else if (angle === 180) {
        uvArray[baseIdx + 0] = u2; uvArray[baseIdx + 1] = v2;
        uvArray[baseIdx + 2] = u1; uvArray[baseIdx + 3] = v2;
        uvArray[baseIdx + 4] = u2; uvArray[baseIdx + 5] = v1;
        uvArray[baseIdx + 6] = u1; uvArray[baseIdx + 7] = v1;
      } else if (angle === 270) {
        uvArray[baseIdx + 0] = u2; uvArray[baseIdx + 1] = v1;
        uvArray[baseIdx + 2] = u2; uvArray[baseIdx + 3] = v2;
        uvArray[baseIdx + 4] = u1; uvArray[baseIdx + 5] = v1;
        uvArray[baseIdx + 6] = u1; uvArray[baseIdx + 7] = v2;
      } else {
        // No rotation (default)
        uvArray[baseIdx + 0] = u1; uvArray[baseIdx + 1] = v1;
        uvArray[baseIdx + 2] = u2; uvArray[baseIdx + 3] = v1;
        uvArray[baseIdx + 4] = u1; uvArray[baseIdx + 5] = v2;
        uvArray[baseIdx + 6] = u2; uvArray[baseIdx + 7] = v2;
      }
    }
  }
  uvAttr.needsUpdate = true;
}

/**
 * Create a quad mesh from shape data
 */
function createQuadMesh(shape, color, texture = null, nodeName = '') {
  const settings = shape.settings;
  if (!settings || !settings.size) return null;

  const stretch = shape.stretch || { x: 1, y: 1, z: 1 };
  const sx = Math.abs(stretch.x || 1);
  const sy = Math.abs(stretch.y || 1);
  const sz = Math.abs(stretch.z || 1);

  const flipX = (stretch.x || 1) < 0;
  const flipY = (stretch.y || 1) < 0;

  const normal = settings.normal || '+Z';
  const pixelW = settings.size.x;
  const pixelH = settings.size.y;

  let width, height;
  if (normal === '+Z' || normal === '-Z') {
    width = pixelW * sx * SCALE;
    height = pixelH * sy * SCALE;
  } else if (normal === '+X' || normal === '-X') {
    width = pixelW * sz * SCALE;
    height = pixelH * sy * SCALE;
  } else {
    width = pixelW * sx * SCALE;
    height = pixelH * sz * SCALE;
  }

  const geometry = new THREE.PlaneGeometry(width, height);

  // Rotate based on normal direction
  if (normal === '-Z') {
    geometry.rotateY(Math.PI);
  } else if (normal === '+X') {
    geometry.rotateY(Math.PI / 2);
  } else if (normal === '-X') {
    geometry.rotateY(-Math.PI / 2);
  } else if (normal === '+Y') {
    geometry.rotateX(-Math.PI / 2);
  } else if (normal === '-Y') {
    geometry.rotateX(Math.PI / 2);
  }

  // Apply UV mapping if texture layout provided
  const hasTextureLayout = texture && shape.textureLayout && shape.textureLayout.front;
  if (hasTextureLayout) {
    const texW = texture.userData?.width || 64;
    const texH = texture.userData?.height || 64;

    const layout = shape.textureLayout.front;
    if (layout && layout.offset) {
      const angle = layout.angle || 0;

      let uv_size = [pixelW, pixelH];
      let uv_mirror = [
        layout.mirror?.x ? -1 : 1,
        layout.mirror?.y ? -1 : 1
      ];
      const uv_offset = [layout.offset.x, layout.offset.y];

      // Calculate UV result based on angle
      let result;
      switch (angle) {
        case 90:
          [uv_size[0], uv_size[1]] = [uv_size[1], uv_size[0]];
          [uv_mirror[0], uv_mirror[1]] = [uv_mirror[1], uv_mirror[0]];
          uv_mirror[0] *= -1;
          result = [
            uv_offset[0],
            uv_offset[1] + uv_size[1] * uv_mirror[1],
            uv_offset[0] + uv_size[0] * uv_mirror[0],
            uv_offset[1]
          ];
          break;
        case 180:
          uv_mirror[0] *= -1;
          uv_mirror[1] *= -1;
          result = [
            uv_offset[0] + uv_size[0] * uv_mirror[0],
            uv_offset[1] + uv_size[1] * uv_mirror[1],
            uv_offset[0],
            uv_offset[1]
          ];
          break;
        case 270:
          [uv_size[0], uv_size[1]] = [uv_size[1], uv_size[0]];
          [uv_mirror[0], uv_mirror[1]] = [uv_mirror[1], uv_mirror[0]];
          uv_mirror[1] *= -1;
          result = [
            uv_offset[0] + uv_size[0] * uv_mirror[0],
            uv_offset[1],
            uv_offset[0],
            uv_offset[1] + uv_size[1] * uv_mirror[1]
          ];
          break;
        default: // 0 degrees
          result = [
            uv_offset[0],
            uv_offset[1],
            uv_offset[0] + uv_size[0] * uv_mirror[0],
            uv_offset[1] + uv_size[1] * uv_mirror[1]
          ];
          break;
      }

      // Convert to normalized UV coordinates with Y flip for WebGL
      const u1 = result[0] / texW;
      const v1 = 1.0 - result[1] / texH;
      const u2 = result[2] / texW;
      const v2 = 1.0 - result[3] / texH;

      // FIX P2: Validate UV coordinates - NaN check
      if (isNaN(u1) || isNaN(v1) || isNaN(u2) || isNaN(v2)) {
        console.warn(`[NativeRenderer] Invalid quad UV coordinates for ${nodeName}, using defaults`);
      } else {
        // PlaneGeometry UV vertex order: bottom-left, bottom-right, top-left, top-right
        let newUVs;
        if (angle === 90) {
          newUVs = new Float32Array([u1, v2, u1, v1, u2, v2, u2, v1]);
        } else if (angle === 180) {
          newUVs = new Float32Array([u2, v2, u1, v2, u2, v1, u1, v1]);
        } else if (angle === 270) {
          newUVs = new Float32Array([u2, v1, u2, v2, u1, v1, u1, v2]);
        } else {
          newUVs = new Float32Array([u1, v1, u2, v1, u1, v2, u2, v2]);
        }

        geometry.setAttribute('uv', new THREE.BufferAttribute(newUVs, 2));
      }
    }
  }

  // Alpha-clipped geometry retains depth, including facial parts behind hair.
  let material;
  if (texture) {
    material = createCachedMaterial({
      map: texture,
      color: 0xffffff,
      alphaTest: 1 / 255,
      transparent: true,
      side: THREE.DoubleSide,
      depthWrite: true,
      depthTest: true
    });
  } else {
    material = createCachedMaterial({
      color: color,
      side: THREE.DoubleSide
    });
  }

  const mesh = new THREE.Mesh(reuseGeometry(geometry, shape, texture), material);

  if (flipX) mesh.scale.x = -1;
  if (flipY) mesh.scale.y = -1;

  return mesh;
}

/**
 * Apply transform to Three.js group
 */
function applyTransform(group, node) {
  if (node.orientation) {
    group.quaternion.set(
      node.orientation.x ?? 0,
      node.orientation.y ?? 0,
      node.orientation.z ?? 0,
      node.orientation.w ?? 1
    );
  }

  let posX = (node.position?.x || 0) * SCALE;
  let posY = (node.position?.y || 0) * SCALE;
  let posZ = (node.position?.z || 0) * SCALE;

  if (node.shape && node.shape.offset) {
    const offset = new THREE.Vector3(
      (node.shape.offset.x || 0) * SCALE,
      (node.shape.offset.y || 0) * SCALE,
      (node.shape.offset.z || 0) * SCALE
    );
    offset.applyQuaternion(group.quaternion);
    posX += offset.x;
    posY += offset.y;
    posZ += offset.z;
  }

  group.position.set(posX, posY, posZ);
}

/**
 * Render a player node recursively
 */
function renderPlayerNode(node, parent, skinColor, bodyTexture, hiddenParts = new Set(), polygonOffsetParts = new Set()) {
  const nodeName = node.name || node.id || '';

  // Skip hidden body parts
  if (hiddenParts.has(nodeName)) {
    const group = new THREE.Group();
    group.name = nodeName;
    applyTransform(group, node);
    parent.add(group);
    if (node.children) {
      for (const child of node.children) {
        renderPlayerNode(child, group, skinColor, bodyTexture, hiddenParts, polygonOffsetParts);
      }
    }
    return;
  }

  const group = new THREE.Group();
  group.name = nodeName;
  applyTransform(group, node);

  if (node.shape && node.shape.visible !== false && node.shape.type !== 'none') {
    let mesh = null;
    if (node.shape.type === 'box') {
      mesh = createBoxMesh(node.shape, skinColor, bodyTexture, nodeName);
    } else if (node.shape.type === 'quad') {
      mesh = createQuadMesh(node.shape, skinColor, bodyTexture, nodeName);
    }
    if (mesh) {
      if (polygonOffsetParts.has(nodeName)) {
        setMaterialState(mesh, { polygonOffset: true, polygonOffsetFactor: 1, polygonOffsetUnits: 1 });
      }
      group.add(mesh);
    }
  }

  parent.add(group);

  if (node.children) {
    for (const child of node.children) {
      renderPlayerNode(child, group, skinColor, bodyTexture, hiddenParts, polygonOffsetParts);
    }
  }
}

/**
 * Count transparent geometry; Three.js handles ordinary object sorting.
 * Native portraits do not implement browser depth peeling.
 */
function prepareTransparency(scene) {
  // Native portraits use Three.js object sorting and alpha-clipped depth writing.
  // This is not browser depth peeling: do not invent category ordering or disable depth.
  let count = 0;
  scene.traverse(object => {
    if (object.isMesh && object.visible && object.material?.transparent) count++;
  });
  return count;
}

/**
 * Render a cosmetic node recursively
 * FIX P0: Properly apply zOffset as local position for attached bones
 * Uses original model textures without synthesized eye backgrounds.
 */
function renderCosmeticNode(node, parent, character, color, texture = null, partType = '', zOffset = 0) {
  const nodeName = node.name || node.id || '';

  let targetParent = parent;
  let attachedToPlayerBone = false;

  if (nodeName && node.shape?.settings?.isPiece === true) {
    const matchingBone = character.getObjectByName(nodeName) || character.getObjectByName(nodeName + '_cosmetic');
    if (matchingBone) {
      targetParent = matchingBone;
      attachedToPlayerBone = true;
    }
  }

  if (attachedToPlayerBone) {
    // Game assembly aliases piece roots to the existing bone and appends only children.
    for (const child of node.children || []) {
      renderCosmeticNode(child, targetParent, character, color, texture, partType, zOffset);
    }
    return;
  }

  const group = new THREE.Group();
  group.name = nodeName + '_cosmetic';

  applyTransform(group, node);
  if (zOffset) group.position.z += zOffset;

  if (node.shape && node.shape.visible !== false && node.shape.type !== 'none') {
    let mesh = null;

    // DEBUG: Log shape data for face cosmetics
    if (['face', 'eyes', 'eyebrows', 'mouth', 'facialHair'].includes(partType)) {
      if (DEBUG_RENDER) console.log(`[SSR DEBUG] ${partType}/${nodeName}: type=${node.shape.type}, hasTexture=${!!texture}, hasTextureLayout=${!!node.shape.textureLayout}, settings=${JSON.stringify(node.shape.settings)}`);
    }

    if (node.shape.type === 'box') {
      mesh = createBoxMesh(node.shape, color, texture, nodeName);
    } else if (node.shape.type === 'quad') {
      mesh = createQuadMesh(node.shape, color, texture, nodeName);
    }

    if (mesh) group.add(mesh);
  }

  targetParent.add(group);

  if (node.children) {
    for (const child of node.children) {
      const childName = child.name || child.id || '';
      const childBone = character.getObjectByName(childName) || character.getObjectByName(childName + '_cosmetic');
      if (childBone && child.shape?.settings?.isPiece === true) {
        renderCosmeticNode(child, childBone, character, color, texture, partType, zOffset);
      } else {
        // Don't apply zOffset again for nested children (already applied at top level)
        renderCosmeticNode(child, group, character, color, texture, partType, 0);
      }
    }
  }
}

/**
 * Build the full character from model data
 */
async function buildCharacter(modelData, character) {
  const skinColor = getSkinToneColor(modelData.skinTone);
  const skinColorHex = '#' + skinColor.toString(16).padStart(6, '0');
  const skinToneGradient = getSkinToneGradientPath(modelData.skinTone);

  // Determine hidden parts based on equipped cosmetics
  const hiddenParts = new Set();
  const polygonOffsetParts = new Set();
  if (modelData.parts?.pants || modelData.parts?.overpants) {
    // Shorts leave skin exposed; retain the underlying legs and offset their depth.
    for (const name of ['Pelvis', 'L-Thigh', 'R-Thigh', 'L-Calf', 'R-Calf']) {
      polygonOffsetParts.add(name);
    }
  }
  if (modelData.parts?.overtop || modelData.parts?.undertop) {
    polygonOffsetParts.add('Belly');
    polygonOffsetParts.add('Chest');
    for (const name of ['L-Arm', 'R-Arm', 'L-Forearm', 'R-Forearm']) polygonOffsetParts.add(name);
  }
  if (modelData.parts?.shoes) {
    polygonOffsetParts.add('L-Foot');
    polygonOffsetParts.add('R-Foot');
  }
  // Note: 'HeadTop' and 'HairBase' don't exist in Player.blockymodel
  // Haircuts are separate cosmetic models that attach to the Head bone

  // Load player base model
  try {
    const bodyPart = modelData.body || modelData.parts?.bodyCharacteristic;
    const bodyModelPath = bodyPart?.model || 'Characters/Player.blockymodel';
    const playerModel = loadModel(bodyModelPath);
    if (playerModel) {

      // Load body texture
      const bodyTexturePath = bodyPart?.greyscaleTexture || (modelData.bodyType === 'Muscular'
        ? 'Characters/Player_Textures/Player_Muscular_Greyscale.png'
        : 'Characters/Player_Textures/Player_Greyscale.png');

      const bodyTexture = await createTintedTexture(bodyTexturePath, skinColorHex, skinToneGradient);

      // Render player nodes
      if (playerModel.nodes) {
        for (const node of playerModel.nodes) {
          renderPlayerNode(node, character, skinColor, bodyTexture, hiddenParts, polygonOffsetParts);
        }
      }
    }
  } catch (err) {
    console.error('[NativeRenderer] Error loading player model:', err.message);
  }

  // Cosmetics render order with zOffset (matching browser version)
  // Surface separation is authored in the models; do not move cosmetic planes.
  const cosmeticOrder = [
    { key: 'skinFeature', zOffset: 0 },
    { key: 'underwear', zOffset: 0 },
    { key: 'pants', zOffset: 0 },
    { key: 'overpants', zOffset: 0 },
    { key: 'shoes', zOffset: 0 },
    { key: 'undertop', zOffset: 0 },
    { key: 'overtop', zOffset: 0 },
    { key: 'gloves', zOffset: 0 },
    { key: 'ears', zOffset: 0 },
    { key: 'face', zOffset: 0 },
    { key: 'facialHair', zOffset: 0 },
    { key: 'mouth', zOffset: 0 },
    { key: 'eyes', zOffset: 0 },
    { key: 'eyebrows', zOffset: 0 },
    { key: 'haircut', zOffset: 0 },
    { key: 'headAccessory', zOffset: 0 },
    { key: 'faceAccessory', zOffset: 0 },
    { key: 'earAccessory', zOffset: 0 },
    { key: 'cape', zOffset: 0 }
  ];

  for (const { key, zOffset } of cosmeticOrder) {
    const part = modelData.parts?.[key];

    // Debug logging for cosmetic loading
    if (key === 'haircut' || key === 'facialHair') {
      if (DEBUG_RENDER) console.log(`[NativeRenderer] Processing ${key}:`, {
        partExists: !!part,
        hasModel: !!part?.model,
        model: part?.model,
        greyscaleTexture: part?.greyscaleTexture,
        texture: part?.texture
      });
    }

    if (part && part.model) {
      // Normalize baseColor (can be string, number, or array)
      let rawBaseColor = part.baseColor;
      if (Array.isArray(rawBaseColor)) {
        rawBaseColor = rawBaseColor[0]; // Use first color from array
      }

      let color = null;
      if (rawBaseColor) {
        if (typeof rawBaseColor === 'string') {
          color = parseInt(rawBaseColor.replace('#', ''), 16);
        } else if (typeof rawBaseColor === 'number') {
          color = rawBaseColor;
        }
      }
      if (!color) {
        if (['face', 'ears'].includes(key)) {
          color = skinColor;
        } else {
          color = DEFAULT_COLORS[key] || 0x888888;
        }
      }

      let texture = null;
      const isSkinPart = part.gradientSet === 'Skin' || ['face', 'ears', 'mouth'].includes(key);

      // DEBUG: Log texture loading paths
      if (DEBUG_RENDER) console.log(`[SSR TEXTURE] ${key}: texture=${part.texture}, greyscale=${part.greyscaleTexture}, gradientTexture=${part.gradientTexture}, isSkinPart=${isSkinPart}`);

      if (part.texture) {
        texture = await loadTextureFromAsset(part.texture);
        if (DEBUG_RENDER) console.log(`[SSR TEXTURE] ${key}: Direct texture loaded = ${!!texture}`);
      } else if (part.greyscaleTexture) {
        let gradientPath = part.gradientTexture;
        let baseCol = rawBaseColor;

        if (isSkinPart) {
          gradientPath = skinToneGradient;
          baseCol = skinColorHex;
        }

        if (DEBUG_RENDER) console.log(`[SSR TEXTURE] ${key}: Creating tinted texture - greyscale=${part.greyscaleTexture}, gradient=${gradientPath}, baseCol=${baseCol}`);
        texture = await createTintedTexture(part.greyscaleTexture, baseCol, gradientPath);
        if (DEBUG_RENDER) console.log(`[SSR TEXTURE] ${key}: Tinted texture created = ${!!texture}`);
      } else {
        if (DEBUG_RENDER) console.log(`[SSR TEXTURE] ${key}: No texture path provided!`);
      }

      if ((part.texture || part.greyscaleTexture) && !texture) {
        throw new Error(`Texture unavailable for ${key}`);
      }

      // Create eye shadow texture for eyes (matching browser behavior)

      try {
        let modelPath = part.model;
        if (!modelPath.startsWith('Common/')) modelPath = 'Common/' + modelPath;
        const model = loadModel(modelPath);
        if (model) {
          if (model.nodes) {
            if (key === 'haircut' || key === 'facialHair' || key === 'eyes') {
              if (DEBUG_RENDER) console.log(`[NativeRenderer] Loaded ${key} model from ${modelPath}:`, {
                nodeCount: model.nodes.length,
                rootNodeNames: model.nodes.map(n => n.name || n.id),
                hasTexture: !!texture,
                preservesTextureAlpha: true
              });
            }
            for (const node of model.nodes) {
              renderCosmeticNode(node, character, character, color, texture, key, 0);
            }
          }
        } else {
          console.warn(`[NativeRenderer] Model not found: ${modelPath}`);
        }
      } catch (err) {
        console.error(`[NativeRenderer] Error loading cosmetic ${key}:`, err.message);
      }
    }
  }
}

/**
 * Render an avatar head to PNG buffer
 */
async function renderHead(uuid, bgColor = 'black', width = 200, height = 200) {
  if (!isAvailable()) {
    throw new Error('Native renderer not available');
  }

  const startTime = Date.now();
  const timings = {};

  // Get user data
  timings.dataStart = Date.now();
  const userData = await storage.getUserData(uuid);
  const userSkin = userData?.skin;

  if (!userSkin) {
    throw new Error('User skin not found');
  }

  // Resolve model data (same as avatar route)
  const configs = assets.loadCosmeticConfigs();
  const gradientSets = assets.loadGradientSets();
  const eyeColors = assets.loadEyeColors();

  if (!configs) {
    throw new Error('Could not load cosmetic configs');
  }

  const resolvedParts = {};
  const categories = [
    'haircut', 'pants', 'overtop', 'undertop', 'shoes',
    'headAccessory', 'faceAccessory', 'earAccessory',
    'eyebrows', 'eyes', 'face', 'facialHair', 'gloves',
    'cape', 'overpants', 'mouth', 'ears', 'underwear', 'bodyCharacteristic', 'skinFeature'
  ];

  for (const category of categories) {
    if (userSkin[category]) {
      const resolved = assets.resolveSkinPart(category, userSkin[category], configs, gradientSets);
      if (resolved) {
        resolvedParts[category] = resolved;
      }
    }
  }

  assets.applyEyeColor(resolvedParts, userSkin.eyeColor, eyeColors, gradientSets);
  assets.applyCosmeticCompatibility(resolvedParts, configs, gradientSets);

  // Parse body characteristic
  let bodyType = 'Regular';
  let skinTone = '01';
  let skinToneFromBody = false;

  if (userSkin.bodyCharacteristic) {
    const bodyParts = userSkin.bodyCharacteristic.split('.');
    bodyType = bodyParts[0] || 'Regular';
    if (bodyParts.length > 1 && bodyParts[1]) {
      skinTone = bodyParts[1].padStart(2, '0');
      skinToneFromBody = true;
    }
  }

  if (!skinToneFromBody && userSkin.skinTone) {
    const toneParts = userSkin.skinTone.split('.');
    const toneValue = toneParts.length > 1 ? toneParts[1] : toneParts[0];
    if (toneValue && toneValue !== 'Default') {
      skinTone = toneValue.padStart(2, '0');
    }
  }

  const modelData = {
    uuid,
    skinTone,
    bodyType,
    parts: resolvedParts
  };
  timings.dataEnd = Date.now();

  // Create renderer
  timings.renderSetup = Date.now();
  const { renderer, glContext } = acquireRenderer(width, height);

  // Create scene
  const scene = new THREE.Scene();

  // Parse background color
  if (bgColor === 'transparent') {
    scene.background = null;
    renderer.setClearColor(0x000000, 0);
  } else if (bgColor === 'white') {
    scene.background = new THREE.Color(0xffffff);
    renderer.setClearColor(0xffffff, 1);
  } else if (bgColor === 'black') {
    scene.background = new THREE.Color(0x000000);
    renderer.setClearColor(0x000000, 1);
  } else if (bgColor.startsWith('#')) {
    const hexColor = parseInt(bgColor.slice(1), 16);
    scene.background = new THREE.Color(hexColor);
    renderer.setClearColor(hexColor, 1);
  }

  // Camera setup for head view
  const camera = new THREE.PerspectiveCamera(40, width / height, 0.1, 100);
  camera.position.set(0, 1.1, -1.0);
  camera.lookAt(0, 1.0, 0);

  // FIX: In-Game Lighting Model (Bright, Vibrant, matches game)
  const hemiLight = new THREE.HemisphereLight(0xffffff, 0x444444, 0.8);
  hemiLight.position.set(0, 20, 0);
  scene.add(hemiLight);

  // Main key light (front/top)
  const frontLight = new THREE.DirectionalLight(0xffffff, 0.6);
  frontLight.position.set(10, 20, 20);
  scene.add(frontLight);

  // Rim/back light (separates head from background)
  const backLight = new THREE.DirectionalLight(0xffffff, 0.4);
  backLight.position.set(-5, 5, -10);
  scene.add(backLight);

  // Character group
  const character = new THREE.Group();
  character.rotation.y = Math.PI; // Face camera
  scene.add(character);
  timings.renderSetupEnd = Date.now();

  // Build character
  timings.buildStart = Date.now();
  await buildCharacter(modelData, character);
  timings.buildEnd = Date.now();

  // Prepare ordinary native transparency (not browser depth peeling).
  timings.oitStart = Date.now();
  const transparentCount = prepareTransparency(scene);
  timings.oitEnd = Date.now();

  // Render
  timings.render = Date.now();
  renderer.render(scene, camera);
  timings.renderEnd = Date.now();

  // Extract pixels
  timings.extractStart = Date.now();
  const pixels = new Uint8Array(width * height * 4);
  glContext.readPixels(0, 0, width, height, glContext.RGBA, glContext.UNSIGNED_BYTE, pixels);

  // Pixel extraction preserves the configured linear output without another conversion.

  // Flip vertically (OpenGL is bottom-up)
  const flipped = new Uint8Array(width * height * 4);
  const rowSize = width * 4;
  for (let y = 0; y < height; y++) {
    const srcRow = y * rowSize;
    const dstRow = (height - 1 - y) * rowSize;
    flipped.set(pixels.subarray(srcRow, srcRow + rowSize), dstRow);
  }

  // Convert to PNG
  const pngBuffer = await sharp(Buffer.from(flipped), {
    raw: { width, height, channels: 4 }
  }).png().toBuffer();
  timings.extractEnd = Date.now();

  // Cleanup
  disposeScene(scene);

  const totalTime = Date.now() - startTime;

  return {
    buffer: pngBuffer,
    timings: {
      total: totalTime,
      dataLoad: timings.dataEnd - timings.dataStart,
      renderSetup: timings.renderSetupEnd - timings.renderSetup,
      characterBuild: timings.buildEnd - timings.buildStart,
      oitSort: timings.oitEnd - timings.oitStart,
      render: timings.renderEnd - timings.render,
      extract: timings.extractEnd - timings.extractStart
    }
  };
}

/**
 * Render an avatar head from direct skin data (for mock/test skins)
 * @param {Object} skinData - Raw skin data (haircut, eyes, face, etc.)
 * @param {string} bgColor - Background color
 * @param {number} width - Image width
 * @param {number} height - Image height
 */
async function renderHeadFromSkinData(skinData, bgColor = 'black', width = 200, height = 200) {
  if (!isAvailable()) {
    throw new Error('Native renderer not available');
  }

  const startTime = Date.now();
  const timings = {};

  timings.dataStart = Date.now();

  // Resolve model data from skin
  const configs = assets.loadCosmeticConfigs();
  const gradientSets = assets.loadGradientSets();
  const eyeColors = assets.loadEyeColors();

  if (!configs) {
    throw new Error('Could not load cosmetic configs');
  }

  const resolvedParts = {};
  const categories = [
    'haircut', 'pants', 'overtop', 'undertop', 'shoes',
    'headAccessory', 'faceAccessory', 'earAccessory',
    'eyebrows', 'eyes', 'face', 'facialHair', 'gloves',
    'cape', 'overpants', 'mouth', 'ears', 'underwear', 'bodyCharacteristic', 'skinFeature'
  ];

  for (const category of categories) {
    if (skinData[category]) {
      const resolved = assets.resolveSkinPart(category, skinData[category], configs, gradientSets);
      if (resolved) {
        resolvedParts[category] = resolved;
      }
    }
  }

  assets.applyEyeColor(resolvedParts, skinData.eyeColor, eyeColors, gradientSets);
  assets.applyCosmeticCompatibility(resolvedParts, configs, gradientSets);

  // Parse body characteristic
  let bodyType = 'Regular';
  let skinTone = '01';
  let skinToneFromBody = false;

  if (skinData.bodyCharacteristic) {
    const bodyParts = skinData.bodyCharacteristic.split('.');
    bodyType = bodyParts[0] || 'Regular';
    if (bodyParts.length > 1 && bodyParts[1]) {
      skinTone = bodyParts[1].padStart(2, '0');
      skinToneFromBody = true;
    }
  }

  if (!skinToneFromBody && skinData.skinTone) {
    const toneParts = skinData.skinTone.split('.');
    const toneValue = toneParts.length > 1 ? toneParts[1] : toneParts[0];
    if (toneValue && toneValue !== 'Default') {
      skinTone = toneValue.padStart(2, '0');
    }
  }

  const modelData = {
    uuid: 'mock-' + Date.now(),
    skinTone,
    bodyType,
    parts: resolvedParts
  };
  timings.dataEnd = Date.now();

  // Create renderer
  timings.renderSetup = Date.now();
  const { renderer, glContext } = acquireRenderer(width, height);

  // Create scene
  const scene = new THREE.Scene();

  // Parse background color
  if (bgColor === 'transparent') {
    scene.background = null;
    renderer.setClearColor(0x000000, 0);
  } else if (bgColor === 'white') {
    scene.background = new THREE.Color(0xffffff);
    renderer.setClearColor(0xffffff, 1);
  } else if (bgColor === 'black') {
    scene.background = new THREE.Color(0x000000);
    renderer.setClearColor(0x000000, 1);
  } else if (bgColor.startsWith('#')) {
    const hexColor = parseInt(bgColor.slice(1), 16);
    scene.background = new THREE.Color(hexColor);
    renderer.setClearColor(hexColor, 1);
  }

  // Camera setup for head view
  const camera = new THREE.PerspectiveCamera(40, width / height, 0.1, 100);
  camera.position.set(0, 1.1, -1.0);
  camera.lookAt(0, 1.0, 0);

  // FIX: In-Game Lighting Model (Bright, Vibrant, matches game)
  const hemiLight = new THREE.HemisphereLight(0xffffff, 0x444444, 0.8);
  hemiLight.position.set(0, 20, 0);
  scene.add(hemiLight);

  const frontLight = new THREE.DirectionalLight(0xffffff, 0.6);
  frontLight.position.set(10, 20, 20);
  scene.add(frontLight);

  const backLight = new THREE.DirectionalLight(0xffffff, 0.4);
  backLight.position.set(-5, 5, -10);
  scene.add(backLight);

  // Character group
  const character = new THREE.Group();
  character.rotation.y = Math.PI;
  scene.add(character);
  timings.renderSetupEnd = Date.now();

  // Build character
  timings.buildStart = Date.now();
  await buildCharacter(modelData, character);
  timings.buildEnd = Date.now();

  // Prepare ordinary native transparency (not browser depth peeling).
  timings.oitStart = Date.now();
  prepareTransparency(scene);
  timings.oitEnd = Date.now();

  // Render
  timings.render = Date.now();
  renderer.render(scene, camera);
  timings.renderEnd = Date.now();

  // Extract pixels
  timings.extractStart = Date.now();
  const pixels = new Uint8Array(width * height * 4);
  glContext.readPixels(0, 0, width, height, glContext.RGBA, glContext.UNSIGNED_BYTE, pixels);

  // Pixel extraction preserves the configured linear output.

  // Flip vertically
  const flipped = new Uint8Array(width * height * 4);
  const rowSize = width * 4;
  for (let y = 0; y < height; y++) {
    const srcRow = y * rowSize;
    const dstRow = (height - 1 - y) * rowSize;
    flipped.set(pixels.subarray(srcRow, srcRow + rowSize), dstRow);
  }

  // Convert to PNG
  const pngBuffer = await sharp(Buffer.from(flipped), {
    raw: { width, height, channels: 4 }
  }).png().toBuffer();
  timings.extractEnd = Date.now();

  // Cleanup
  disposeScene(scene);

  const totalTime = Date.now() - startTime;

  return {
    buffer: pngBuffer,
    timings: {
      total: totalTime,
      dataLoad: timings.dataEnd - timings.dataStart,
      renderSetup: timings.renderSetupEnd - timings.renderSetup,
      characterBuild: timings.buildEnd - timings.buildStart,
      oitSort: timings.oitEnd - timings.oitStart,
      render: timings.renderEnd - timings.render,
      extract: timings.extractEnd - timings.extractStart
    }
  };
}

/**
 * Render full body from skin data (for testing body cosmetics)
 */
async function renderFullBodyFromSkinData(skinData, bgColor = 'black', width = 400, height = 600) {
  if (!isAvailable()) {
    throw new Error('Native renderer not available');
  }

  const startTime = Date.now();
  const timings = {};

  timings.dataStart = Date.now();

  // Resolve model data from skin (same as renderHeadFromSkinData)
  const configs = assets.loadCosmeticConfigs();
  const gradientSets = assets.loadGradientSets();
  const eyeColors = assets.loadEyeColors();

  if (!configs) {
    throw new Error('Could not load cosmetic configs');
  }

  const resolvedParts = {};
  const categories = [
    'haircut', 'pants', 'overtop', 'undertop', 'shoes',
    'headAccessory', 'faceAccessory', 'earAccessory',
    'eyebrows', 'eyes', 'face', 'facialHair', 'gloves',
    'cape', 'overpants', 'mouth', 'ears', 'underwear', 'bodyCharacteristic', 'skinFeature'
  ];

  for (const category of categories) {
    if (skinData[category]) {
      const resolved = assets.resolveSkinPart(category, skinData[category], configs, gradientSets);
      if (resolved) {
        resolvedParts[category] = resolved;
      }
    }
  }

  assets.applyEyeColor(resolvedParts, skinData.eyeColor, eyeColors, gradientSets);
  assets.applyCosmeticCompatibility(resolvedParts, configs, gradientSets);

  // Parse body characteristic
  let bodyType = 'Regular';
  let skinTone = '01';
  let skinToneFromBody = false;

  if (skinData.bodyCharacteristic) {
    const bodyParts = skinData.bodyCharacteristic.split('.');
    bodyType = bodyParts[0] || 'Regular';
    if (bodyParts.length > 1 && bodyParts[1]) {
      skinTone = bodyParts[1].padStart(2, '0');
      skinToneFromBody = true;
    }
  }

  if (!skinToneFromBody && skinData.skinTone) {
    const toneParts = skinData.skinTone.split('.');
    const toneValue = toneParts.length > 1 ? toneParts[1] : toneParts[0];
    if (toneValue && toneValue !== 'Default') {
      skinTone = toneValue.padStart(2, '0');
    }
  }

  const modelData = {
    uuid: 'mock-fullbody-' + Date.now(),
    skinTone,
    bodyType,
    parts: resolvedParts
  };
  timings.dataEnd = Date.now();

  // Create renderer
  timings.renderSetup = Date.now();
  const { renderer, glContext } = acquireRenderer(width, height);

  // Create scene
  const scene = new THREE.Scene();

  // Parse background color
  if (bgColor === 'transparent') {
    scene.background = null;
    renderer.setClearColor(0x000000, 0);
  } else if (bgColor === 'white') {
    scene.background = new THREE.Color(0xffffff);
    renderer.setClearColor(0xffffff, 1);
  } else if (bgColor === 'black') {
    scene.background = new THREE.Color(0x000000);
    renderer.setClearColor(0x000000, 1);
  } else if (bgColor.startsWith('#')) {
    const hexColor = parseInt(bgColor.slice(1), 16);
    scene.background = new THREE.Color(hexColor);
    renderer.setClearColor(hexColor, 1);
  }

  // Camera setup for FULL BODY view (different from head view)
  const camera = new THREE.PerspectiveCamera(45, width / height, 0.1, 100);
  camera.position.set(0, 0.6, -2.5);  // Further back, centered on body
  camera.lookAt(0, 0.5, 0);

  // FIX: In-Game Lighting Model (Bright, Vibrant, matches game)
  const hemiLight = new THREE.HemisphereLight(0xffffff, 0x444444, 0.8);
  hemiLight.position.set(0, 20, 0);
  scene.add(hemiLight);

  const frontLight = new THREE.DirectionalLight(0xffffff, 0.6);
  frontLight.position.set(10, 20, 20);
  scene.add(frontLight);

  const backLight = new THREE.DirectionalLight(0xffffff, 0.4);
  backLight.position.set(-5, 5, -10);
  scene.add(backLight);

  // Character group
  const character = new THREE.Group();
  character.rotation.y = Math.PI;
  scene.add(character);
  timings.renderSetupEnd = Date.now();

  // Build character
  timings.buildStart = Date.now();
  await buildCharacter(modelData, character);
  timings.buildEnd = Date.now();

  // Prepare ordinary native transparency (not browser depth peeling).
  timings.oitStart = Date.now();
  prepareTransparency(scene);
  timings.oitEnd = Date.now();

  // Render
  timings.render = Date.now();
  renderer.render(scene, camera);
  timings.renderEnd = Date.now();

  // Extract pixels
  timings.extractStart = Date.now();
  const pixels = new Uint8Array(width * height * 4);
  glContext.readPixels(0, 0, width, height, glContext.RGBA, glContext.UNSIGNED_BYTE, pixels);

  // Flip vertically
  const flipped = new Uint8Array(width * height * 4);
  const rowSize = width * 4;
  for (let y = 0; y < height; y++) {
    const srcRow = y * rowSize;
    const dstRow = (height - 1 - y) * rowSize;
    flipped.set(pixels.subarray(srcRow, srcRow + rowSize), dstRow);
  }

  // Convert to PNG
  const pngBuffer = await sharp(Buffer.from(flipped), {
    raw: { width, height, channels: 4 }
  }).png().toBuffer();
  timings.extractEnd = Date.now();

  // Cleanup
  disposeScene(scene);

  const totalTime = Date.now() - startTime;

  return {
    buffer: pngBuffer,
    timings: {
      total: totalTime,
      dataLoad: timings.dataEnd - timings.dataStart,
      renderSetup: timings.renderSetupEnd - timings.renderSetup,
      characterBuild: timings.buildEnd - timings.buildStart,
      oitSort: timings.oitEnd - timings.oitStart,
      render: timings.renderEnd - timings.render,
      extract: timings.extractEnd - timings.extractStart
    }
  };
}

/**
 * Get renderer status info
 */
function getStatus() {
  return {
    available: isAvailable(),
    error: initError?.message || null,
    resources: { contextsCreated, activeContexts: sharedRenderer ? 1 : 0, queuedRenders, textures: textureCache.size, models: modelCache.size, materials: materialCache.size, geometries: geometryCache.size },
    dependencies: {
      three: THREE !== null,
      gl: createContext !== null,
      sharp: sharp !== null,
      canvas: false // DataTexture rendering does not require native canvas
    }
  };
}

module.exports = {
  init,
  isAvailable,
  getInitError,
  getStatus,
  renderHeadFromSkinData: (...args) => queueRender(() => renderHeadFromSkinData(...args)),
  renderFullBodyFromSkinData: (...args) => queueRender(() => renderFullBodyFromSkinData(...args)),
  renderHead: (...args) => queueRender(() => renderHead(...args)),
  resetRenderResources,
  createRenderer,
  loadTextureFromAsset,
  createTintedTexture
};
