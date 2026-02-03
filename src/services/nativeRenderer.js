/**
 * Native Server-Side Renderer for Hytale Avatars
 * Uses headless-gl (gl) + Three.js + sharp for GPU-free rendering
 */

const path = require('path');
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
let createCanvas = null;
let rendererAvailable = false;
let initError = null;

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
    const canvas = require('canvas');
    createCanvas = canvas.createCanvas;

    rendererAvailable = true;
    console.log('[NativeRenderer] Dependencies loaded successfully');
  } catch (err) {
    initError = err;
    rendererAvailable = false;
    console.warn('[NativeRenderer] Dependencies not available:', err.message);
    console.warn('[NativeRenderer] Install with: npm install three gl canvas sharp');
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
    THREE.ColorManagement.enabled = true;
  }

  // Create headless WebGL context with high precision
  const glContext = createContext(width, height, {
    preserveDrawingBuffer: true,
    antialias: true,
    precision: 'highp'  // Explicitly request high precision
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

  // Match browser Three.js default settings
  renderer.toneMapping = THREE.NoToneMapping;
  renderer.toneMappingExposure = 1.0;

  // Use LINEAR output - we'll apply gamma correction manually after readPixels
  // This ensures consistent results in headless-gl environment
  if (THREE.LinearSRGBColorSpace) {
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

  try {
    const buffer = assets.extractAsset(texturePath);
    if (!buffer) {
      console.log(`[NativeRenderer] Texture not found: ${texturePath}`);
      return null;
    }

    // Use sharp to decode the image
    const image = sharp(buffer);
    const metadata = await image.metadata();
    const { data, info } = await image
      .ensureAlpha()
      .raw()
      .toBuffer({ resolveWithObject: true });

    // Create a canvas to hold the image data
    const canvas = createCanvas(info.width, info.height);
    const ctx = canvas.getContext('2d');
    const imageData = ctx.createImageData(info.width, info.height);

    // Copy raw RGBA data
    for (let i = 0; i < data.length; i++) {
      imageData.data[i] = data[i];
    }
    ctx.putImageData(imageData, 0, 0);

    // Create Three.js texture from canvas
    const texture = new THREE.CanvasTexture(canvas);
    texture.magFilter = THREE.NearestFilter;
    texture.minFilter = THREE.NearestFilter;
    texture.wrapS = THREE.ClampToEdgeWrapping;
    texture.wrapT = THREE.ClampToEdgeWrapping;
    texture.generateMipmaps = false;
    texture.userData = { width: info.width, height: info.height };

    // Force sRGB color space - bypasses "Unsupported image type" warning
    // by explicitly telling Three.js how to treat this texture
    if (THREE.SRGBColorSpace) {
      texture.colorSpace = THREE.SRGBColorSpace;
    } else if (texture.encoding !== undefined) {
      texture.encoding = THREE.sRGBEncoding;
    }
    texture.needsUpdate = true;

    return texture;
  } catch (err) {
    console.error(`[NativeRenderer] Error loading texture ${texturePath}:`, err.message);
    return null;
  }
}

/**
 * Create a tinted texture from greyscale
 */
async function createTintedTexture(greyscalePath, baseColor, gradientPath = null) {
  const buffer = assets.extractAsset(greyscalePath);
  if (!buffer) return null;

  try {
    const image = sharp(buffer);
    const { data, info } = await image
      .ensureAlpha()
      .raw()
      .toBuffer({ resolveWithObject: true });

    // Load gradient if provided
    let gradientData = null;
    if (gradientPath) {
      const gradientBuffer = assets.extractAsset(gradientPath);
      if (gradientBuffer) {
        const gradient = sharp(gradientBuffer);
        const gradientResult = await gradient.ensureAlpha().raw().toBuffer({ resolveWithObject: true });
        gradientData = gradientResult.data;
      }
    }

    // Parse base color
    const color = parseColor(baseColor);

    // Apply tinting
    for (let i = 0; i < data.length; i += 4) {
      const origR = data[i];
      const origG = data[i + 1];
      const origB = data[i + 2];
      const alpha = data[i + 3];

      if (alpha > 0) {
        const isGreyscale = (origR === origG) && (origG === origB);

        if (isGreyscale) {
          const grey = origR;
          let r, g, b;

          if (gradientData) {
            const gradX = Math.min(grey, Math.floor(gradientData.length / 4) - 1);
            const gradIdx = gradX * 4;
            r = gradientData[gradIdx];
            g = gradientData[gradIdx + 1];
            b = gradientData[gradIdx + 2];
          } else if (color) {
            const t = grey / 255;
            r = Math.round(Math.min(255, color.r * t * 2));
            g = Math.round(Math.min(255, color.g * t * 2));
            b = Math.round(Math.min(255, color.b * t * 2));
          } else {
            r = grey; g = grey; b = grey;
          }

          data[i] = r;
          data[i + 1] = g;
          data[i + 2] = b;
        }
      }
    }

    // Create canvas with tinted data
    const canvas = createCanvas(info.width, info.height);
    const ctx = canvas.getContext('2d');
    const imageData = ctx.createImageData(info.width, info.height);
    for (let i = 0; i < data.length; i++) {
      imageData.data[i] = data[i];
    }
    ctx.putImageData(imageData, 0, 0);

    // Create texture
    const texture = new THREE.CanvasTexture(canvas);
    texture.magFilter = THREE.NearestFilter;
    texture.minFilter = THREE.NearestFilter;
    texture.wrapS = THREE.ClampToEdgeWrapping;
    texture.wrapT = THREE.ClampToEdgeWrapping;
    texture.generateMipmaps = false;
    texture.userData = { width: info.width, height: info.height };

    // Force sRGB color space - bypasses "Unsupported image type" warning
    if (THREE.SRGBColorSpace) {
      texture.colorSpace = THREE.SRGBColorSpace;
    } else if (texture.encoding !== undefined) {
      texture.encoding = THREE.sRGBEncoding;
    }
    texture.needsUpdate = true;

    return texture;
  } catch (err) {
    console.error(`[NativeRenderer] Error creating tinted texture:`, err.message);
    return null;
  }
}

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

  // Apply UV mapping if texture layout provided
  if (texture && shape.textureLayout) {
    applyBoxUVs(geometry, shape, texture);
  }

  // Determine if we need double-sided rendering
  const modelDoubleSided = shape.doubleSided === true;
  const needsDoubleSide = modelDoubleSided || flipX || flipY || flipZ;

  // Body parts use solid materials, cosmetics use transparent with alpha clipping
  const isBodyPart = ['Neck', 'Head', 'Chest', 'Belly', 'Pelvis'].includes(nodeName) ||
                     nodeName.includes('Arm') || nodeName.includes('Leg') ||
                     nodeName.includes('Hand') || nodeName.includes('Foot') ||
                     nodeName.includes('Thigh') || nodeName.includes('Calf');

  let material;
  if (texture) {
    material = new THREE.MeshStandardMaterial({
      map: texture,
      color: 0xffffff,
      alphaTest: 0.5,           // Sharp edges, discards semi-transparent pixels
      transparent: true,         // Required for alpha to work
      side: THREE.DoubleSide,    // See back of hair/neck
      depthWrite: !isBodyPart ? false : true,  // Prevent black ghost artifacts on cosmetics
      roughness: 0.5,            // Smoother for more "pop"
      metalness: 0.0
    });
  } else {
    material = new THREE.MeshStandardMaterial({
      color: color,
      side: THREE.DoubleSide,
      roughness: 0.5,
      metalness: 0.0
    });
  }

  const mesh = new THREE.Mesh(geometry, material);

  if (flipX) mesh.scale.x = -1;
  if (flipY) mesh.scale.y = -1;
  if (flipZ) mesh.scale.z = -1;

  return mesh;
}

/**
 * Apply UV mapping to box geometry
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
      let uv_size = [0, 0];
      if (faceName === 'left' || faceName === 'right') {
        uv_size = [pixelD, pixelH];
      } else if (faceName === 'top' || faceName === 'bottom') {
        uv_size = [pixelW, pixelD];
      } else {
        uv_size = [pixelW, pixelH];
      }

      const uv_offset = [layout.offset.x, layout.offset.y];
      const u1 = uv_offset[0] / texW;
      const v1 = 1.0 - uv_offset[1] / texH;
      const u2 = (uv_offset[0] + uv_size[0]) / texW;
      const v2 = 1.0 - (uv_offset[1] + uv_size[1]) / texH;

      const baseIdx = faceIdx * 4 * 2;
      uvArray[baseIdx + 0] = u1; uvArray[baseIdx + 1] = v1;
      uvArray[baseIdx + 2] = u2; uvArray[baseIdx + 3] = v1;
      uvArray[baseIdx + 4] = u1; uvArray[baseIdx + 5] = v2;
      uvArray[baseIdx + 6] = u2; uvArray[baseIdx + 7] = v2;
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

  let material;
  if (texture) {
    material = new THREE.MeshStandardMaterial({
      map: texture,
      color: 0xffffff,
      alphaTest: 0.5,
      transparent: true,
      side: THREE.DoubleSide,
      depthWrite: false,    // Quads are typically overlays, prevent black artifacts
      depthTest: true,
      roughness: 0.5,
      metalness: 0.0
    });
  } else {
    material = new THREE.MeshStandardMaterial({
      color: color,
      side: THREE.DoubleSide,
      roughness: 0.5,
      metalness: 0.0
    });
  }

  const mesh = new THREE.Mesh(geometry, material);

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
function renderPlayerNode(node, parent, skinColor, bodyTexture, hiddenParts = new Set()) {
  const nodeName = node.name || node.id || '';

  // Skip hidden body parts
  if (hiddenParts.has(nodeName)) {
    const group = new THREE.Group();
    group.name = nodeName;
    applyTransform(group, node);
    parent.add(group);
    if (node.children) {
      for (const child of node.children) {
        renderPlayerNode(child, group, skinColor, bodyTexture, hiddenParts);
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
    if (mesh) group.add(mesh);
  }

  parent.add(group);

  if (node.children) {
    for (const child of node.children) {
      renderPlayerNode(child, group, skinColor, bodyTexture, hiddenParts);
    }
  }
}

/**
 * Render a cosmetic node recursively
 */
function renderCosmeticNode(node, parent, character, color, texture = null, partType = '', zOffset = 0) {
  const nodeName = node.name || node.id || '';

  let targetParent = parent;
  let attachedToPlayerBone = false;

  if (nodeName) {
    const matchingBone = character.getObjectByName(nodeName);
    if (matchingBone) {
      targetParent = matchingBone;
      attachedToPlayerBone = true;
    }
  }

  const group = new THREE.Group();
  group.name = nodeName + '_cosmetic';

  if (attachedToPlayerBone) {
    if (node.orientation) {
      group.quaternion.set(
        node.orientation.x ?? 0,
        node.orientation.y ?? 0,
        node.orientation.z ?? 0,
        node.orientation.w ?? 1
      );
    }
  } else {
    applyTransform(group, node);
  }

  // Apply zOffset to prevent z-fighting
  if (zOffset) {
    group.position.z += zOffset;
  }

  if (node.shape && node.shape.visible !== false && node.shape.type !== 'none') {
    let mesh = null;
    if (node.shape.type === 'box') {
      mesh = createBoxMesh(node.shape, color, texture, nodeName);
    } else if (node.shape.type === 'quad') {
      mesh = createQuadMesh(node.shape, color, texture, nodeName);
      // Set render order for proper layering
      if (mesh) {
        if (partType === 'eyes') {
          if (nodeName.includes('Background')) {
            mesh.renderOrder = 100;
            mesh.material.transparent = true;
            mesh.material.depthWrite = false;
          } else if (nodeName.includes('Eye') && !nodeName.includes('Attachment')) {
            mesh.renderOrder = 101;
          }
        } else if (partType === 'mouth') {
          mesh.renderOrder = 99;
        } else if (partType === 'face') {
          mesh.renderOrder = 98;
        }
      }
    }
    if (mesh) group.add(mesh);
  }

  targetParent.add(group);

  if (node.children) {
    for (const child of node.children) {
      const childName = child.name || child.id || '';
      const childBone = character.getObjectByName(childName);
      if (childBone) {
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
  if (modelData.parts?.pants || modelData.parts?.overpants) {
    hiddenParts.add('Pelvis');
    hiddenParts.add('L-Thigh');
    hiddenParts.add('R-Thigh');
    hiddenParts.add('L-Calf');
    hiddenParts.add('R-Calf');
  }
  if (modelData.parts?.overtop || modelData.parts?.undertop) {
    hiddenParts.add('Belly');
    hiddenParts.add('Chest');
  }
  if (modelData.parts?.shoes) {
    hiddenParts.add('L-Foot');
    hiddenParts.add('R-Foot');
  }
  if (modelData.parts?.haircut) {
    hiddenParts.add('HeadTop');
    hiddenParts.add('HairBase');
  }

  // Load player base model
  try {
    const playerModelBuffer = assets.extractAsset('Common/Characters/Player.blockymodel');
    if (playerModelBuffer) {
      const playerModel = JSON.parse(playerModelBuffer.toString());

      // Load body texture
      const bodyTexturePath = modelData.bodyType === 'Muscular'
        ? 'Characters/Player_Textures/Player_Muscular_Greyscale.png'
        : 'Characters/Player_Textures/Player_Greyscale.png';

      const bodyTexture = await createTintedTexture(bodyTexturePath, skinColorHex, skinToneGradient);

      // Render player nodes
      if (playerModel.nodes) {
        for (const node of playerModel.nodes) {
          renderPlayerNode(node, character, skinColor, bodyTexture, hiddenParts);
        }
      }
    }
  } catch (err) {
    console.error('[NativeRenderer] Error loading player model:', err.message);
  }

  // Cosmetics render order with zOffset (matching browser version)
  const cosmeticOrder = [
    { key: 'underwear', zOffset: 0 },
    { key: 'pants', zOffset: 0.001 },
    { key: 'overpants', zOffset: 0.002 },
    { key: 'shoes', zOffset: 0.001 },
    { key: 'undertop', zOffset: 0.001 },
    { key: 'overtop', zOffset: 0.002 },
    { key: 'gloves', zOffset: 0.001 },
    { key: 'face', zOffset: 0.01 },
    { key: 'mouth', zOffset: 0.015 },
    { key: 'eyes', zOffset: 0.02 },
    { key: 'eyebrows', zOffset: 0.025 },
    { key: 'ears', zOffset: 0 },
    { key: 'haircut', zOffset: 0.005 },
    { key: 'facialHair', zOffset: 0.004 },
    { key: 'headAccessory', zOffset: 0.006 },
    { key: 'faceAccessory', zOffset: 0.005 },
    { key: 'earAccessory', zOffset: 0.001 },
    { key: 'cape', zOffset: -0.001 }
  ];

  for (const { key, zOffset } of cosmeticOrder) {
    const part = modelData.parts?.[key];
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

      if (part.texture) {
        texture = await loadTextureFromAsset(part.texture);
      } else if (part.greyscaleTexture) {
        let gradientPath = part.gradientTexture;
        let baseCol = rawBaseColor;

        if (isSkinPart) {
          gradientPath = skinToneGradient;
          baseCol = skinColorHex;
        }

        texture = await createTintedTexture(part.greyscaleTexture, baseCol, gradientPath);
      }

      try {
        let modelPath = part.model;
        if (!modelPath.startsWith('Common/')) modelPath = 'Common/' + modelPath;
        const modelBuffer = assets.extractAsset(modelPath);
        if (modelBuffer) {
          const model = JSON.parse(modelBuffer.toString());
          if (model.nodes) {
            for (const node of model.nodes) {
              renderCosmeticNode(node, character, character, color, texture, key, zOffset);
            }
          }
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
    'cape', 'overpants', 'mouth', 'ears', 'underwear'
  ];

  for (const category of categories) {
    if (userSkin[category]) {
      const resolved = assets.resolveSkinPart(category, userSkin[category], configs, gradientSets);
      if (resolved) {
        resolvedParts[category] = resolved;
      }
    }
  }

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
  const { renderer, glContext } = createRenderer(width, height);

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

  // Lighting - HemisphereLight provides omni-directional fill (fixes black neck/undersides)
  // Sky color (top) is bright white, ground color (bottom) is soft gray
  const hemiLight = new THREE.HemisphereLight(0xffffff, 0x222222, 1.0);
  hemiLight.position.set(0, 20, 0);
  scene.add(hemiLight);

  // Main key light (front/top)
  const frontLight = new THREE.DirectionalLight(0xffffff, 1.0);
  frontLight.position.set(10, 20, 20);
  scene.add(frontLight);

  // Rim/back light (separates head from background)
  const backLight = new THREE.DirectionalLight(0xffffff, 0.8);
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

  // Render
  timings.render = Date.now();
  renderer.render(scene, camera);
  timings.renderEnd = Date.now();

  // Extract pixels
  timings.extractStart = Date.now();
  const pixels = new Uint8Array(width * height * 4);
  glContext.readPixels(0, 0, width, height, glContext.RGBA, glContext.UNSIGNED_BYTE, pixels);

  // Apply gamma correction (Linear to sRGB)
  // Renderer outputs in Linear space, we manually convert to sRGB for correct display
  // This ensures consistent results in headless-gl environment
  const gamma = 1 / 2.2;
  for (let i = 0; i < pixels.length; i += 4) {
    pixels[i] = Math.round(Math.pow(pixels[i] / 255, gamma) * 255);     // R
    pixels[i + 1] = Math.round(Math.pow(pixels[i + 1] / 255, gamma) * 255); // G
    pixels[i + 2] = Math.round(Math.pow(pixels[i + 2] / 255, gamma) * 255); // B
    // Alpha stays the same (pixels[i + 3])
  }

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
  renderer.dispose();

  const totalTime = Date.now() - startTime;

  return {
    buffer: pngBuffer,
    timings: {
      total: totalTime,
      dataLoad: timings.dataEnd - timings.dataStart,
      renderSetup: timings.renderSetupEnd - timings.renderSetup,
      characterBuild: timings.buildEnd - timings.buildStart,
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
    dependencies: {
      three: THREE !== null,
      gl: createContext !== null,
      sharp: sharp !== null,
      canvas: createCanvas !== null
    }
  };
}

module.exports = {
  init,
  isAvailable,
  getInitError,
  getStatus,
  renderHead,
  createRenderer,
  loadTextureFromAsset,
  createTintedTexture
};
