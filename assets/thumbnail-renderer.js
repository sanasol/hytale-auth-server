/**
 * ThumbnailRenderer - Renders 3D cosmetic item previews for customizer grid
 *
 * This renderer creates 3D thumbnails by:
 * 1. Using a hidden container with a small canvas
 * 2. Building a partial character (only relevant body parts)
 * 3. Adding the cosmetic item
 * 4. Rendering once and extracting as data URL
 *
 * Properly reuses HytaleAvatarViewer patterns for model loading/rendering.
 */

const THUMB_SIZE = 128;
const THUMB_SCALE = 0.01; // Same as avatar.js
const DEBUG_THUMBNAILS = false;
const DEBUG_TEXTURES = false;

// Camera settings per category region
// Based on actual body part world positions:
// - Head center: ~0.88
// - Chest center: ~0.71
// - Belly center: ~0.63
// - Pelvis: ~0.51
// - Thighs: ~0.39
// - Calves: ~0.28
// - Feet: ~0.27
const CAMERA_CONFIGS = {
  // Head: 45-degree angle view, further back to fit whole head/haircut
  head: {
    position: new THREE.Vector3(-0.7, 1.1, -1.3),
    lookAt: new THREE.Vector3(0, 0.95, 0),
    fov: 28
  },
  torso: {
    position: new THREE.Vector3(0, 0.68, -1.4),
    lookAt: new THREE.Vector3(0, 0.62, 0),
    fov: 40
  },
  // Gloves: Focus on right hand only with closer camera
  gloves: {
    position: new THREE.Vector3(-0.35, 0.48, -0.6),
    lookAt: new THREE.Vector3(-0.18, 0.45, 0),
    fov: 35
  },
  legs: {
    position: new THREE.Vector3(0, 0.38, -1.6),
    lookAt: new THREE.Vector3(0, 0.35, 0),
    fov: 38
  },
  // Cape: Camera behind character to see the back (character still faces front with Math.PI)
  cape: {
    position: new THREE.Vector3(0, 0.68, 1.1),
    lookAt: new THREE.Vector3(0, 0.62, 0),
    fov: 45
    // No characterRotation override - uses default Math.PI so character faces -Z
    // Camera at +Z sees the back of the character
  }
};

// Map cosmetic categories to camera regions
const CATEGORY_TO_REGION = {
  haircut: 'head',
  eyebrows: 'head',
  eyes: 'head',
  face: 'head',
  mouth: 'head',
  facialHair: 'head',
  headAccessory: 'head',
  faceAccessory: 'head',
  earAccessory: 'head',
  ears: 'head',
  undertop: 'torso',
  overtop: 'torso',
  gloves: 'gloves',  // Uses dedicated gloves camera config
  pants: 'legs',
  overpants: 'legs',
  underwear: 'legs',
  shoes: 'legs',
  cape: 'cape',
  skinFeature: 'torso',
  bodyCharacteristic: 'torso'
};

// Body parts to render per region
// Note: We render ALL body parts to maintain hierarchy for cosmetic attachment,
// but we visually show only relevant ones by hiding others
const REGION_BODY_PARTS = {
  head: new Set([
    'Head', 'HeadTop', 'HairBase', 'Neck', 'NeckTop',
    'L-Eyelid', 'R-Eyelid', 'L-Eyelid-Bot', 'R-Eyelid-Bot',
    // Attachment points
    'L-Eyebrow-Attachment', 'R-Eyebrow-Attachment',
    'L-Eye-Attachment', 'R-Eye-Attachment',
    'Mouth-Attachment', 'L-Ear-Attachment', 'R-Ear-Attachment'
  ]),
  torso: new Set([
    'Pelvis', 'Belly', 'Chest', 'Neck', 'NeckTop',
    'L-Shoulder', 'R-Shoulder', 'L-Arm', 'R-Arm',
    'L-Forearm', 'R-Forearm', 'L-Shoulder2', 'R-Shoulder2',
    'Back-Attachment'
  ]),
  // Gloves: Show only right arm/hand for focused preview
  gloves: new Set([
    'R-Shoulder', 'R-Arm', 'R-Forearm', 'R-Hand', 'R-Attachment'
  ]),
  legs: new Set([
    'Pelvis', 'L-Thigh', 'R-Thigh', 'L-Calf', 'R-Calf',
    'L-Foot', 'R-Foot'
  ]),
  cape: new Set([
    'Pelvis', 'Belly', 'Chest', 'Neck', 'NeckTop',
    'L-Shoulder', 'R-Shoulder', 'L-Shoulder2', 'R-Shoulder2',
    'Back-Attachment'
  ])
};

// Parts to hide when rendering specific cosmetics
const HIDE_PARTS_FOR_COSMETIC = {
  pants: [], // Short garments require the base legs underneath.
  overpants: [],
  underwear: [],
  shoes: [],
  overtop: [],
  undertop: [],
  haircut: ['HeadTop', 'HairBase'],
  gloves: []
};

class ThumbnailRenderer {
  constructor() {
    this.container = null;
    this.renderer = null;
    this.scene = null;
    this.camera = null;
    this.textureLoader = null;
    this.textureCache = new Map();
    this.modelCache = new Map();
    this.thumbnailCache = new Map();
    this.initialized = false;

    // Reusable groups
    this.character = null;
    this.lights = null;

    // Render queue to prevent concurrent renders
    this.renderQueue = [];
    this.isRendering = false;
  }

  async init() {
    if (this.initialized) return;

    // Create hidden container
    this.container = document.createElement('div');
    this.container.style.cssText = 'position:absolute;left:-9999px;top:-9999px;width:128px;height:128px;';
    document.body.appendChild(this.container);

    // Create renderer
    this.renderer = new THREE.WebGLRenderer({
      antialias: true,
      alpha: true,
      preserveDrawingBuffer: true
    });
    this.renderer.setSize(THUMB_SIZE, THUMB_SIZE);
    this.renderer.setPixelRatio(1);
    this.renderer.setClearColor(0x000000, 0);
    this.renderer.autoClear = true;
    this.container.appendChild(this.renderer.domElement);

    // Create scene
    this.scene = new THREE.Scene();

    // Create camera (will be configured per render)
    this.camera = new THREE.PerspectiveCamera(45, 1, 0.1, 100);

    // Setup lights - FIX P0: Reduced intensity to ~1.0 total (was 1.8x causing overexposure)
    this.lights = new THREE.Group();
    this.lights.add(new THREE.AmbientLight(0xffffff, 0.4));

    const dirLight = new THREE.DirectionalLight(0xffffff, 0.35);
    dirLight.position.set(2, 3, 2);
    this.lights.add(dirLight);

    const backLight = new THREE.DirectionalLight(0xffffff, 0.15);
    backLight.position.set(-2, 1, -2);
    this.lights.add(backLight);

    const fillLight = new THREE.DirectionalLight(0xffffff, 0.1);
    fillLight.position.set(0, -1, 2);
    this.lights.add(fillLight);

    this.scene.add(this.lights);

    // Character group
    this.character = new THREE.Group();
    this.scene.add(this.character);

    this.textureLoader = new THREE.TextureLoader();
    this.initialized = true;

    console.log('[ThumbnailRenderer] Initialized');
  }

  /**
   * Render a thumbnail for a cosmetic item
   * @param {string} category - Cosmetic category (e.g., 'haircut', 'cape')
   * @param {string} itemId - Item ID
   * @param {string} colorId - Color variant ID (optional)
   * @param {string} skinTone - Skin tone (default '01')
   * @returns {Promise<string|null>} Data URL or null on failure
   */
  async renderThumbnail(category, itemId, colorId, skinTone = '01') {
    if (!this.initialized) {
      await this.init();
    }

    // Check cache first (can return immediately without queueing)
    const cacheKey = `${category}:${itemId}:${colorId || 'default'}:${skinTone}`;
    if (this.thumbnailCache.has(cacheKey)) {
      if (DEBUG_THUMBNAILS) console.log('[ThumbnailRenderer] Cache hit:', cacheKey);
      return this.thumbnailCache.get(cacheKey);
    }

    // Queue the render to prevent concurrent access to shared renderer
    return new Promise((resolve) => {
      this.renderQueue.push({ category, itemId, colorId, skinTone, cacheKey, resolve });
      this._processQueue();
    });
  }

  async _processQueue() {
    if (this.isRendering || this.renderQueue.length === 0) {
      return;
    }

    this.isRendering = true;

    while (this.renderQueue.length > 0) {
      const { category, itemId, colorId, skinTone, cacheKey, resolve } = this.renderQueue.shift();

      // Double-check cache (might have been rendered while in queue)
      if (this.thumbnailCache.has(cacheKey)) {
        resolve(this.thumbnailCache.get(cacheKey));
        continue;
      }

      if (DEBUG_THUMBNAILS) console.log(`[ThumbnailRenderer] Rendering ${category}/${itemId} color=${colorId} skin=${skinTone}`);

      try {
        const dataUrl = await this._doRender(category, itemId, colorId, skinTone, cacheKey);
        resolve(dataUrl);
      } catch (err) {
        console.error(`[ThumbnailRenderer] Error rendering ${category}/${itemId}:`, err);
        resolve(null);
      }
    }

    this.isRendering = false;
  }

  async _doRender(category, itemId, colorId, skinTone, cacheKey) {
    try {
      // Clear previous scene
      this._clearCharacter();

      // Get camera config for this category
      const region = CATEGORY_TO_REGION[category] || 'torso';
      const cameraConfig = CAMERA_CONFIGS[region];

      if (DEBUG_THUMBNAILS) console.log(`[ThumbnailRenderer] Region: ${region}, Camera pos: (${cameraConfig.position.x}, ${cameraConfig.position.y}, ${cameraConfig.position.z})`);

      // Position camera
      this.camera.fov = cameraConfig.fov;
      this.camera.position.copy(cameraConfig.position);
      this.camera.lookAt(cameraConfig.lookAt);
      this.camera.updateProjectionMatrix();

      // Set character rotation (face camera or show back for capes)
      this.character.rotation.y = cameraConfig.characterRotation !== undefined
        ? cameraConfig.characterRotation
        : Math.PI;

      // Get skin color info
      const skinColor = this._getSkinToneColor(skinTone);
      const skinColorHex = '#' + skinColor.toString(16).padStart(6, '0');
      const skinToneGradient = this._getSkinToneGradientPath(skinTone);

      // Build partial body for this region
      const bodyParts = REGION_BODY_PARTS[region] || new Set();
      const hideParts = new Set(HIDE_PARTS_FOR_COSMETIC[category] || []);

      await this._buildPartialBody(bodyParts, hideParts, skinColor, skinColorHex, skinToneGradient);

      // Fetch item data from API
      const itemData = await this._fetchItemData(category, itemId);
      if (!itemData) {
        console.warn(`[ThumbnailRenderer] No item data for ${category}/${itemId}`);
        return null;
      }

      // Resolve item texture/model paths
      const resolvedPart = this._resolveItemPart(itemData, colorId);
      if (!resolvedPart || !resolvedPart.model) {
        console.warn(`[ThumbnailRenderer] No model for ${category}/${itemId}`);
        return null;
      }

      // Load and render the cosmetic
      await this._renderCosmetic(category, resolvedPart, skinColor, skinColorHex, skinToneGradient);

      // Render and capture
      this.renderer.clear();
      this.renderer.render(this.scene, this.camera);
      const dataUrl = this.renderer.domElement.toDataURL('image/png');

      // Cache result
      this.thumbnailCache.set(cacheKey, dataUrl);

      return dataUrl;
    } catch (err) {
      console.error(`[ThumbnailRenderer] Error in _doRender:`, err);
      return null;
    }
  }

  _clearCharacter() {
    // Recursively dispose all objects in the character group
    this.character.traverse((obj) => {
      if (obj.geometry) obj.geometry.dispose();
      if (obj.material) {
        if (Array.isArray(obj.material)) {
          obj.material.forEach(m => {
            if (m.map && !this.textureCache.has(m.map._cacheKey)) {
              m.map.dispose();
            }
            m.dispose();
          });
        } else {
          if (obj.material.map && !this.textureCache.has(obj.material.map._cacheKey)) {
            obj.material.map.dispose();
          }
          obj.material.dispose();
        }
      }
    });

    // Remove all children
    while (this.character.children.length > 0) {
      this.character.remove(this.character.children[0]);
    }

    // Reset character transform
    this.character.position.set(0, 0, 0);
    this.character.rotation.set(0, 0, 0);
    this.character.scale.set(1, 1, 1);
  }

  _disposeObject(obj) {
    if (obj.geometry) obj.geometry.dispose();
    if (obj.material) {
      if (Array.isArray(obj.material)) {
        obj.material.forEach(m => {
          // Don't dispose textures that might be in the cache
          // Just dispose the material itself
          if (m.map && !this.textureCache.has(m.map._cacheKey)) {
            m.map.dispose();
          }
          m.dispose();
        });
      } else {
        // Don't dispose textures that might be in the cache
        if (obj.material.map && !this.textureCache.has(obj.material.map._cacheKey)) {
          obj.material.map.dispose();
        }
        obj.material.dispose();
      }
    }
    if (obj.children) {
      obj.children.forEach(c => this._disposeObject(c));
    }
  }

  async _buildPartialBody(includeParts, hideParts, skinColor, skinColorHex, skinToneGradient) {
    // Load player model
    const playerModel = await this._fetchModel('Common/Characters/Player.blockymodel');
    if (!playerModel) {
      if (DEBUG_THUMBNAILS) console.error('[ThumbnailRenderer] Failed to load Player.blockymodel');
      return;
    }

    if (DEBUG_THUMBNAILS) console.log('[ThumbnailRenderer] Loaded player model, nodes:', playerModel.nodes?.length);

    // Load body texture
    const bodyTexturePath = 'Characters/Player_Textures/Player_Greyscale.png';
    const bodyTexture = await this._createTintedTexture(bodyTexturePath, skinColorHex, skinToneGradient);

    if (DEBUG_THUMBNAILS) console.log('[ThumbnailRenderer] Body texture:', bodyTexture ? 'loaded' : 'FAILED');

    // Render the full model but only show visible parts
    await this._renderPartialPlayerModel(
      playerModel.nodes,
      this.character,
      skinColor,
      bodyTexture,
      includeParts,
      hideParts
    );

    if (DEBUG_THUMBNAILS) {
      let meshCount = 0;
      let visibleCount = 0;
      this.character.traverse((obj) => {
        if (obj.isMesh) {
          meshCount++;
          if (obj.visible) visibleCount++;
        }
      });
      console.log(`[ThumbnailRenderer] Built body: ${meshCount} meshes, ${visibleCount} visible`);
    }
  }

  async _renderPartialPlayerModel(nodes, parent, skinColor, bodyTexture, includeParts, hideParts) {
    if (!nodes) return;
    for (const node of nodes) {
      await this._renderPartialPlayerNode(node, parent, skinColor, bodyTexture, includeParts, hideParts, 0);
    }
  }

  async _renderPartialPlayerNode(node, parent, skinColor, bodyTexture, includeParts, hideParts, depth) {
    const nodeName = node.name || node.id || '';

    const group = new THREE.Group();
    group.name = nodeName;
    this._applyTransform(group, node);

    // Determine if we should render a mesh for this node
    // We need the bone hierarchy intact for cosmetic attachment, but only show relevant parts
    const shouldHide = hideParts.has(nodeName);
    const shouldShow = includeParts.has(nodeName) && !shouldHide;

    // Always render the mesh for bone hierarchy, but make it invisible if not in includeParts
    if (node.shape && node.shape.visible !== false && node.shape.type === 'box') {
      const mesh = this._createBoxMesh(node.shape, skinColor, nodeName, bodyTexture);
      if (mesh) {
        mesh.visible = shouldShow;
        if (['Pelvis', 'L-Thigh', 'R-Thigh', 'L-Calf', 'R-Calf'].includes(nodeName)) {
          mesh.material.polygonOffset = true;
          mesh.material.polygonOffsetFactor = 1;
          mesh.material.polygonOffsetUnits = 1;
        }
        group.add(mesh);
      }
    } else if (node.shape && node.shape.type === 'quad') {
      const mesh = this._createQuadMesh(node.shape, skinColor, nodeName, bodyTexture);
      if (mesh) {
        mesh.visible = shouldShow;
        if (['Pelvis', 'L-Thigh', 'R-Thigh', 'L-Calf', 'R-Calf'].includes(nodeName)) {
          mesh.material.polygonOffset = true;
          mesh.material.polygonOffsetFactor = 1;
          mesh.material.polygonOffsetUnits = 1;
        }
        group.add(mesh);
      }
    }

    parent.add(group);

    // Process all children to maintain full hierarchy
    if (node.children) {
      for (const child of node.children) {
        await this._renderPartialPlayerNode(child, group, skinColor, bodyTexture, includeParts, hideParts, depth + 1);
      }
    }
  }

  async _renderCosmetic(category, part, skinColor, skinColorHex, skinToneGradient) {
    let color = this._getPartColor(part);
    if (!color) {
      if (['face', 'ears', 'mouth'].includes(category)) {
        color = skinColor;
      } else {
        color = this._getDefaultColor(category, skinColor);
      }
    }

    let texture = null;
    const isSkinPart = part.gradientSet === 'Skin' || ['face', 'ears', 'mouth'].includes(category);

    if (part.texture) {
      texture = await this._loadTexture(part.texture);
    } else if (part.greyscaleTexture) {
      let gradientPath = part.gradientTexture;
      let baseCol = part.baseColor;

      if (isSkinPart) {
        gradientPath = skinToneGradient;
        baseCol = skinColorHex;
        color = skinColor;
      }

      if (!gradientPath && !baseCol) {
        baseCol = '#' + this._getDefaultColor(category, skinColor).toString(16).padStart(6, '0');
      }

      texture = await this._createTintedTexture(part.greyscaleTexture, baseCol, gradientPath);
    }

    if (DEBUG_TEXTURES && texture) {
      const w = texture.image?.width || texture.userData?.width;
      const h = texture.image?.height || texture.userData?.height;
      console.log(`[ThumbnailRenderer] Cosmetic texture for ${category}: ${w}x${h}, key=${texture._cacheKey}`);
    }

    if ((part.texture || part.greyscaleTexture) && !texture) {
      throw new Error(`Texture unavailable for ${category}`);
    }

    // Load model
    let modelPath = part.model;
    if (!modelPath.startsWith('Common/')) modelPath = 'Common/' + modelPath;

    if (DEBUG_THUMBNAILS) console.log(`[ThumbnailRenderer] Loading cosmetic model: ${modelPath}`);

    const model = await this._fetchModel(modelPath);

    // Model-authored offsets define the surface separation.
    const zOffset = 0;

    if (model) {
      if (DEBUG_THUMBNAILS) console.log(`[ThumbnailRenderer] Cosmetic model loaded, nodes: ${model.nodes?.length}`);

      await this._renderCosmeticModel(model.nodes, this.character, color, category, texture, zOffset);
    } else {
      if (DEBUG_THUMBNAILS) console.error(`[ThumbnailRenderer] Failed to load cosmetic model: ${modelPath}`);
    }
  }

  async _renderCosmeticModel(nodes, parent, color, partType, texture, zOffset) {
    if (!nodes) return;
    for (const node of nodes) {
      await this._renderCosmeticNode(node, parent, color, partType, texture, zOffset, 0);
    }
  }

  async _renderCosmeticNode(node, parent, color, partType, texture, zOffset, depth) {
    const nodeName = node.name || node.id || '';

    // Find matching bone in character
    let targetParent = parent;
    let attachedToPlayerBone = false;
    if (nodeName && node.shape?.settings?.isPiece === true) {
      const matchingBone = this._findBoneByName(this.character, nodeName);
      if (matchingBone) {
        targetParent = matchingBone;
        attachedToPlayerBone = true;
      }
    }

    if (attachedToPlayerBone) {
      for (const child of node.children || []) {
        await this._renderCosmeticNode(child, targetParent, color, partType, texture, 0, depth + 1);
      }
      return;
    }

    const group = new THREE.Group();
    group.name = nodeName + '_cosmetic';

    if (attachedToPlayerBone) {
      // A piece root uses the existing bone transform, not its template pose.
      group.quaternion.identity();
    } else {
      this._applyTransform(group, node);
    }

    if (zOffset) {
      group.position.z += zOffset;
    }

    // Create mesh
    if (node.shape && node.shape.visible !== false && node.shape.type !== 'none') {
      let mesh = null;
      if (node.shape.type === 'box') {
        mesh = this._createBoxMesh(node.shape, color, nodeName, texture);
      } else if (node.shape.type === 'quad') {
        mesh = this._createQuadMesh(node.shape, color, nodeName, texture);
      }
      if (mesh) group.add(mesh);
    }

    targetParent.add(group);

    // Process children - check if child matches a player bone
    if (node.children) {
      for (const child of node.children) {
        const childName = child.name || child.id || '';
        const childBone = this._findBoneByName(this.character, childName);
        if (childBone && child.shape?.settings?.isPiece === true) {
          // Child matches a player bone, attach to that bone
          await this._renderCosmeticNode(child, childBone, color, partType, texture, zOffset, depth + 1);
        } else {
          // No matching bone, attach to current group with no additional zOffset
          await this._renderCosmeticNode(child, group, color, partType, texture, 0, depth + 1);
        }
      }
    }
  }

  _findBoneByName(parent, name) {
    if (!name) return null;
    let found = null;
    parent.traverse((obj) => {
      if ((obj.name === name || obj.name === name + '_cosmetic') && !found) found = obj;
    });
    return found;
  }

  async _fetchItemData(category, itemId) {
    const cacheKey = `item:${category}:${itemId}`;
    if (this.modelCache.has(cacheKey)) {
      return this.modelCache.get(cacheKey);
    }

    try {
      const response = await fetch(`/cosmetics/item/${category}/${itemId}`);
      if (!response.ok) return null;
      const data = await response.json();
      this.modelCache.set(cacheKey, data);
      return data;
    } catch (e) {
      console.error(`[ThumbnailRenderer] Failed to fetch item ${category}/${itemId}:`, e);
      return null;
    }
  }

  _resolveItemPart(itemData, colorId) {
    if (!itemData || !itemData.item) return null;

    const item = itemData.item;
    const gradientSets = itemData.gradientSets || [];

    const result = {
      model: null,
      texture: null,
      greyscaleTexture: null,
      gradientTexture: null,
      gradientSet: null,
      baseColor: null
    };

    // Helper to get gradient texture path from gradient set
    const getGradientTexture = (gradientSetId, colorIdToUse) => {
      if (!gradientSetId || !colorIdToUse) return null;
      const gradientSetConfig = gradientSets.find(g => g.Id === gradientSetId);
      if (gradientSetConfig && gradientSetConfig.Gradients && gradientSetConfig.Gradients[colorIdToUse]) {
        return {
          texture: gradientSetConfig.Gradients[colorIdToUse].Texture,
          baseColor: gradientSetConfig.Gradients[colorIdToUse].BaseColor
        };
      }
      return null;
    };

    // Handle items with Variants (like capes)
    if (item.Variants) {
      const variant = item.Variants['Neck_Piece'] || Object.values(item.Variants)[0];
      if (variant) {
        result.model = variant.Model;
        result.greyscaleTexture = variant.GreyscaleTexture;

        if (variant.Textures && colorId && variant.Textures[colorId]) {
          result.texture = variant.Textures[colorId].Texture;
          result.baseColor = variant.Textures[colorId].BaseColor;
        } else if (variant.GreyscaleTexture && item.GradientSet) {
          result.gradientSet = item.GradientSet;
          const gradientData = getGradientTexture(item.GradientSet, colorId);
          if (gradientData) {
            result.gradientTexture = gradientData.texture;
            result.baseColor = gradientData.baseColor;
          }
        }
      }
      if (DEBUG_THUMBNAILS) console.log('[_resolveItemPart] Variant item:', { model: result.model, greyscale: result.greyscaleTexture, gradient: result.gradientTexture });
      return result;
    }

    // Standard item without variants
    result.model = item.Model;
    result.greyscaleTexture = item.GreyscaleTexture;
    result.gradientSet = item.GradientSet;

    if (item.Textures && colorId && item.Textures[colorId]) {
      result.texture = item.Textures[colorId].Texture;
      result.baseColor = item.Textures[colorId].BaseColor;
    } else if (item.GreyscaleTexture && item.GradientSet) {
      const gradientData = getGradientTexture(item.GradientSet, colorId);
      if (gradientData) {
        result.gradientTexture = gradientData.texture;
        result.baseColor = gradientData.baseColor;
      }
    }

    if (DEBUG_THUMBNAILS) console.log('[_resolveItemPart] Standard item:', { model: result.model, greyscale: result.greyscaleTexture, gradient: result.gradientTexture, texture: result.texture });

    return result;
  }

  // Texture loading (adapted from avatar.js)
  async _loadTexture(path) {
    if (this.textureCache.has(path)) {
      if (DEBUG_TEXTURES) console.log(`[ThumbnailRenderer] Texture cache HIT: ${path}`);
      return this.textureCache.get(path);
    }
    if (DEBUG_TEXTURES) console.log(`[ThumbnailRenderer] Texture cache MISS, loading: ${path}`);

    const pathsToTry = [
      path.startsWith('Common/') ? '/asset/' + path : '/asset/Common/' + path,
      '/asset/' + path,
      '/asset/' + path.replace('Common/', ''),
      '/asset/Common/' + path.replace('Common/', '')
    ];

    const uniquePaths = [...new Set(pathsToTry)];

    for (const tryPath of uniquePaths) {
      try {
        const texture = await new Promise((resolve, reject) => {
          this.textureLoader.load(tryPath,
            (tex) => {
              tex.magFilter = THREE.NearestFilter;
              tex.minFilter = THREE.NearestFilter;
              tex.wrapS = THREE.ClampToEdgeWrapping;
              tex.wrapT = THREE.ClampToEdgeWrapping;
              tex.generateMipmaps = false;
              // Store dimensions in userData for UV mapping
              if (tex.image) {
                tex.userData = { width: tex.image.width, height: tex.image.height };
              }
              // Mark this texture as cached so we don't dispose it
              tex._cacheKey = path;
              resolve(tex);
            },
            undefined,
            () => reject(new Error('Failed to load'))
          );
        });
        this.textureCache.set(path, texture);
        return texture;
      } catch (e) {
        // Try next path
      }
    }

    return null;
  }

  async _fetchModel(path) {
    if (this.modelCache.has(path)) return this.modelCache.get(path);

    try {
      const response = await fetch('/asset/' + path);
      if (!response.ok) return null;
      const model = await response.json();
      this.modelCache.set(path, model);
      return model;
    } catch (e) {
      return null;
    }
  }

  async _createTintedTexture(greyscalePath, baseColor, gradientPath) {
    // Create a unique cache key for this specific tinted texture
    const cacheKey = `tinted:${greyscalePath}:${baseColor || 'none'}:${gradientPath || 'none'}`;
    if (this.textureCache.has(cacheKey)) {
      if (DEBUG_TEXTURES) console.log(`[ThumbnailRenderer] Tinted texture cache HIT: ${cacheKey}`);
      return this.textureCache.get(cacheKey);
    }
    if (DEBUG_TEXTURES) console.log(`[ThumbnailRenderer] Tinted texture cache MISS, creating: ${cacheKey}`);

    const greyTexture = await this._loadTexture(greyscalePath);
    if (!greyTexture || !greyTexture.image) return null;

    let gradientTexture = null;
    if (gradientPath) {
      gradientTexture = await this._loadTexture(gradientPath);
    }

    const img = greyTexture.image;
    const canvas = document.createElement('canvas');
    canvas.width = img.width;
    canvas.height = img.height;
    const ctx = canvas.getContext('2d');

    ctx.drawImage(img, 0, 0);
    const imageData = ctx.getImageData(0, 0, img.width, img.height);
    const data = imageData.data;

    const color = this._parseColor(baseColor);
    let gradientData = null;
    if (gradientTexture && gradientTexture.image) {
      const gCanvas = document.createElement('canvas');
      gCanvas.width = gradientTexture.image.width;
      gCanvas.height = gradientTexture.image.height;
      const gCtx = gCanvas.getContext('2d');
      gCtx.drawImage(gradientTexture.image, 0, 0);
      gradientData = gCtx.getImageData(0, 0, gCanvas.width, gCanvas.height).data;
    }

    for (let i = 0; i < data.length; i += 4) {
      const origR = data[i];
      const origG = data[i + 1];
      const origB = data[i + 2];
      const alpha = data[i + 3];

      if (alpha > 0) {
        // Only tint greyscale pixels (matching avatar.js behavior)
        const isGreyscale = (origR === origG) && (origG === origB);

        if (isGreyscale) {
          const grey = origR;
          let r, g, b;

          if (gradientData) {
            // Use gradient width to clamp index (matching avatar.js)
            const gradientWidth = gradientTexture.image.width;
            const gradX = Math.min(grey, gradientWidth - 1);
            const gradIdx = gradX * 4;
            r = gradientData[gradIdx];
            g = gradientData[gradIdx + 1];
            b = gradientData[gradIdx + 2];
          } else if (color) {
            // FIX P0: Remove * 2 multiplier that caused color overexposure
            const t = grey / 255;
            r = Math.round(Math.min(255, color.r * t));
            g = Math.round(Math.min(255, color.g * t));
            b = Math.round(Math.min(255, color.b * t));
          } else {
            r = grey; g = grey; b = grey;
          }

          data[i] = r;
          data[i + 1] = g;
          data[i + 2] = b;
        }
      }
    }

    ctx.putImageData(imageData, 0, 0);
    const texture = this._createCanvasTexture(canvas);
    // Mark as cached so we don't dispose it
    texture._cacheKey = cacheKey;
    this.textureCache.set(cacheKey, texture);
    return texture;
  }

  _createCanvasTexture(canvas) {
    const texture = new THREE.CanvasTexture(canvas);
    texture.magFilter = THREE.NearestFilter;
    texture.minFilter = THREE.NearestFilter;
    texture.wrapS = THREE.ClampToEdgeWrapping;
    texture.wrapT = THREE.ClampToEdgeWrapping;
    texture.generateMipmaps = false;
    // Store dimensions in userData for UV mapping (canvas textures don't have .image.width directly accessible in some cases)
    texture.userData = { width: canvas.width, height: canvas.height };
    // Force texture to be uploaded to GPU fresh
    texture.needsUpdate = true;
    return texture;
  }

  // Create eye shadow texture (matching avatar.js)


  _parseColor(color) {
    if (typeof color === 'number') {
      return {
        r: (color >> 16) & 255,
        g: (color >> 8) & 255,
        b: color & 255
      };
    }
    if (typeof color === 'string' && color.startsWith('#')) {
      const hex = color.slice(1);
      return {
        r: parseInt(hex.substr(0, 2), 16),
        g: parseInt(hex.substr(2, 2), 16),
        b: parseInt(hex.substr(4, 2), 16)
      };
    }
    if (Array.isArray(color)) {
      return this._parseColor(color[0]);
    }
    return { r: 200, g: 200, b: 200 };
  }

  _applyTransform(group, node) {
    if (node.orientation) {
      group.quaternion.set(
        node.orientation.x ?? 0,
        node.orientation.y ?? 0,
        node.orientation.z ?? 0,
        node.orientation.w ?? 1
      );
    }

    let posX = (node.position?.x || 0) * THUMB_SCALE;
    let posY = (node.position?.y || 0) * THUMB_SCALE;
    let posZ = (node.position?.z || 0) * THUMB_SCALE;

    // Apply shape offset rotated by orientation (matching avatar.js)
    if (node.shape && node.shape.offset) {
      const offset = new THREE.Vector3(
        (node.shape.offset.x || 0) * THUMB_SCALE,
        (node.shape.offset.y || 0) * THUMB_SCALE,
        (node.shape.offset.z || 0) * THUMB_SCALE
      );
      offset.applyQuaternion(group.quaternion);
      posX += offset.x;
      posY += offset.y;
      posZ += offset.z;
    }

    group.position.set(posX, posY, posZ);
  }

  _createBoxMesh(shape, color, nodeName, texture) {
    // Size is in shape.settings.size (not shape.size)
    const settings = shape.settings;
    if (!settings || !settings.size) return null;

    const stretch = shape.stretch || { x: 1, y: 1, z: 1 };
    const sx = Math.abs(stretch.x || 1);
    const sy = Math.abs(stretch.y || 1);
    const sz = Math.abs(stretch.z || 1);

    const flipX = (stretch.x || 1) < 0;
    const flipY = (stretch.y || 1) < 0;
    const flipZ = (stretch.z || 1) < 0;

    const w = settings.size.x * sx * THUMB_SCALE;
    const h = settings.size.y * sy * THUMB_SCALE;
    const d = settings.size.z * sz * THUMB_SCALE;

    const geometry = new THREE.BoxGeometry(w, h, d);

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

    if (texture && shape.textureLayout) {
      this._applyBoxUVs(geometry, shape, texture, flipX, flipY, flipZ);
    }

    const needsDoubleSide = shape.doubleSided || flipX || flipY || flipZ;

    // Detect body parts (matching avatar.js logic for proper material settings)
    let material;
    if (texture) {
      material = new THREE.MeshLambertMaterial({
        map: texture,
        alphaTest: 1 / 255,
        transparent: true,
        side: needsDoubleSide ? THREE.DoubleSide : THREE.FrontSide,
        depthWrite: true
      });
    } else {
      material = new THREE.MeshLambertMaterial({
        color: color,
        side: needsDoubleSide ? THREE.DoubleSide : THREE.FrontSide
      });
    }

    const mesh = new THREE.Mesh(geometry, material);
    mesh.name = nodeName + '_mesh';

    // Apply flip scaling for negative stretch values
    if (flipX) mesh.scale.x = -1;
    if (flipY) mesh.scale.y = -1;
    if (flipZ) mesh.scale.z = -1;

    return mesh;
  }

  _applyBoxUVs(geometry, shape, texture, flipX, flipY, flipZ) {
    const texW = texture.image?.width || texture.userData?.width;
    const texH = texture.image?.height || texture.userData?.height;
    if (!texW || !texH || !shape.textureLayout) return;

    const settings = shape.settings;
    if (!settings || !settings.size) return;

    const stretch = shape.stretch || { x: 1, y: 1, z: 1 };

    const pixelW = settings.size.x;
    const pixelH = settings.size.y;
    const pixelD = settings.size.z;

    const uvAttr = geometry.getAttribute('uv');
    const uvArray = uvAttr.array;
    const faceMap = ['right', 'left', 'top', 'bottom', 'front', 'back'];

    for (let faceIdx = 0; faceIdx < 6; faceIdx++) {
      const faceName = faceMap[faceIdx];
      const layout = shape.textureLayout[faceName];

      if (layout && layout.offset) {
        const angle = layout.angle || 0;

        let uv_size = [0, 0];
        if (faceName === 'left' || faceName === 'right') {
          uv_size = [pixelD, pixelH];
        } else if (faceName === 'top' || faceName === 'bottom') {
          uv_size = [pixelW, pixelD];
        } else {
          uv_size = [pixelW, pixelH];
        }

        let uv_mirror = [
          layout.mirror?.x ? -1 : 1,
          layout.mirror?.y ? -1 : 1
        ];

        const uv_offset = [layout.offset.x, layout.offset.y];

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
          default:
            result = [
              uv_offset[0],
              uv_offset[1],
              uv_offset[0] + uv_size[0] * uv_mirror[0],
              uv_offset[1] + uv_size[1] * uv_mirror[1]
            ];
            break;
        }

        const u1 = result[0] / texW;
        const v1 = 1.0 - result[1] / texH;
        const u2 = result[2] / texW;
        const v2 = 1.0 - result[3] / texH;

        const baseIdx = faceIdx * 4 * 2;

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
          uvArray[baseIdx + 0] = u1; uvArray[baseIdx + 1] = v1;
          uvArray[baseIdx + 2] = u2; uvArray[baseIdx + 3] = v1;
          uvArray[baseIdx + 4] = u1; uvArray[baseIdx + 5] = v2;
          uvArray[baseIdx + 6] = u2; uvArray[baseIdx + 7] = v2;
        }
      }
    }
    uvAttr.needsUpdate = true;
  }

  _createQuadMesh(shape, color, nodeName, texture) {
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

    // Calculate width/height based on normal direction
    let w, h;
    if (normal === '+Z' || normal === '-Z') {
      w = pixelW * sx * THUMB_SCALE;
      h = pixelH * sy * THUMB_SCALE;
    } else if (normal === '+X' || normal === '-X') {
      w = pixelW * sz * THUMB_SCALE;
      h = pixelH * sy * THUMB_SCALE;
    } else {
      w = pixelW * sx * THUMB_SCALE;
      h = pixelH * sz * THUMB_SCALE;
    }

    const geometry = new THREE.PlaneGeometry(w, h);

    // Rotate geometry based on normal direction
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

    if (texture && shape.textureLayout) {
      this._applyQuadUVs(geometry, shape, texture);
    }

    let material;
    if (texture) {
      material = new THREE.MeshLambertMaterial({
        map: texture,
        transparent: true,
        alphaTest: 1 / 255,
        side: THREE.DoubleSide,
        depthWrite: true,
        depthTest: true
      });
    } else {
      material = new THREE.MeshLambertMaterial({
        color: color,
        side: THREE.DoubleSide
      });
    }

    const mesh = new THREE.Mesh(geometry, material);
    mesh.name = nodeName + '_mesh';

    // Apply flip scaling for negative stretch values
    if (flipX) mesh.scale.x = -1;
    if (flipY) mesh.scale.y = -1;
    return mesh;
  }

  _applyQuadUVs(geometry, shape, texture) {
    const texW = texture.image?.width || texture.userData?.width;
    const texH = texture.image?.height || texture.userData?.height;
    if (!texW || !texH || !shape.textureLayout || !shape.textureLayout.front) return;

    const settings = shape.settings;
    if (!settings || !settings.size) return;

    const pixelW = settings.size.x;
    const pixelH = settings.size.y;

    const layout = shape.textureLayout.front;
    if (!layout.offset) return;

    const angle = layout.angle || 0;
    let uv_size = [pixelW, pixelH];
    let uv_mirror = [
      layout.mirror?.x ? -1 : 1,
      layout.mirror?.y ? -1 : 1
    ];
    const uv_offset = [layout.offset.x, layout.offset.y];

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
      default:
        result = [
          uv_offset[0],
          uv_offset[1],
          uv_offset[0] + uv_size[0] * uv_mirror[0],
          uv_offset[1] + uv_size[1] * uv_mirror[1]
        ];
        break;
    }

    const u1 = result[0] / texW;
    const v1 = 1.0 - result[1] / texH;
    const u2 = result[2] / texW;
    const v2 = 1.0 - result[3] / texH;

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

  // Skin tone color mapping (from avatar.js - exact match)
  _getSkinToneColor(tone) {
    const tones = {
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
    return tones[tone] || tones['01'];
  }

  _getSkinToneGradientPath(tone) {
    // Match avatar.js exactly
    const validTones = ['01','02','03','04','05','06','07','08','09','10','11','12','13','14','15','16','17','18','19','20','21','22','25','26','27','28','29','30','31','32','33','35','36','37','38','39','41','42','45','46','47','48','49','50','51','52'];
    if (validTones.includes(tone)) {
      return `TintGradients/Skin_Tones/${tone}.png`;
    }
    return 'TintGradients/Skin_Tones/01.png';
  }

  _getPartColor(part) {
    if (part.baseColor) {
      const parsed = this._parseColor(part.baseColor);
      return (parsed.r << 16) | (parsed.g << 8) | parsed.b;
    }
    return null;
  }

  _getDefaultColor(partType, skinColor) {
    const defaults = {
      haircut: 0x3d2517,
      eyebrows: 0x3d2517,
      facialHair: 0x3d2517,
      eyes: 0x5391c1,
      face: skinColor,
      ears: skinColor,
      mouth: skinColor,
      pants: 0x4a4a5e,
      overtop: 0x6b8b3d,
      undertop: 0x8b7355,
      shoes: 0x4a3728,
      gloves: 0x5a4a3a,
      cape: 0x8b2942,
      headAccessory: 0xc0a060,
      faceAccessory: 0x888888,
      earAccessory: 0xc0c0c0,
      underwear: 0x888888,
      overpants: 0x555566
    };
    return defaults[partType] || 0x888888;
  }

  /**
   * Clear all caches
   */
  clearCache() {
    this.thumbnailCache.clear();
    // Don't clear model/texture caches as they're reusable
  }

  /**
   * Invalidate thumbnails for a category (e.g., when color changes)
   */
  invalidateCategory(category) {
    const keysToDelete = [];
    for (const key of this.thumbnailCache.keys()) {
      if (key.startsWith(category + ':')) {
        keysToDelete.push(key);
      }
    }
    keysToDelete.forEach(k => this.thumbnailCache.delete(k));
  }

  /**
   * Cleanup resources
   */
  destroy() {
    if (this.renderer) {
      this.renderer.dispose();
    }
    if (this.container && this.container.parentNode) {
      this.container.parentNode.removeChild(this.container);
    }
    this.textureCache.clear();
    this.modelCache.clear();
    this.thumbnailCache.clear();
    this.initialized = false;
  }
}

// Export for use
if (typeof module !== 'undefined' && module.exports) {
  module.exports = ThumbnailRenderer;
}
