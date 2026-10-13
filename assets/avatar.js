/**
 * Hytale Avatar Viewer - Three.js Rendering Module
 *
 * Usage:
 *   const viewer = new HytaleAvatarViewer(containerElement, options);
 *   await viewer.loadAvatar(uuid);
 *   viewer.setAnimation('Default/Idle');
 */

const SCALE = 0.01; // BlockyModel units to world units
const FPS = 60; // Hytale animations run at 60fps

class HytaleAvatarViewer {
  constructor(container, options = {}) {
    this.container = container;
    this.options = {
      autoRotate: options.autoRotate !== false,
      showGrid: options.showGrid !== false,
      backgroundColor: options.backgroundColor || 0x1a1a2e,
      useOIT: options.useOIT !== false, // Enable OIT by default
      oitLayers: options.oitLayers || 4, // Number of depth peeling layers
      ...options
    };

    this.scene = null;
    this.camera = null;
    this.renderer = null;
    this.oitRenderer = null; // Depth peeling OIT renderer
    this.character = null;
    this.modelData = null;
    this.wireframeMode = false;
    this.autoRotate = this.options.autoRotate;

    // Animation state
    this.currentAnimation = null;
    this.animationTime = 0;
    this.lastFrameTime = 0;
    this.animationEnabled = true;
    this.currentAnimationPath = 'Default/Idle';

    // Texture cache
    this.textureCache = new Map();
    this.textureLoader = null;

    // Body parts state
    this.hiddenBodyParts = new Set();
    this.polygonOffsetParts = new Set();
    this.originalTransforms = new Map();

    // Callbacks
    this.onLoadProgress = options.onLoadProgress || (() => {});
    this.onLoadComplete = options.onLoadComplete || (() => {});
    this.onError = options.onError || ((err) => console.error(err));
  }

  // Initialize Three.js scene
  init() {
    const width = this.container.clientWidth;
    const height = this.container.clientHeight;

    this.scene = new THREE.Scene();
    this.scene.background = new THREE.Color(this.options.backgroundColor);

    this.camera = new THREE.PerspectiveCamera(45, width / height, 0.1, 1000);
    this.camera.position.set(0, 0.6, 2.0);
    this.camera.lookAt(0, 0.5, 0);

    this.renderer = new THREE.WebGLRenderer({
      antialias: true,
      alpha: this.options.alpha !== false  // Enable alpha by default for transparency support
    });
    this.renderer.setSize(width, height);
    this.renderer.setPixelRatio(window.devicePixelRatio);
    this.container.appendChild(this.renderer.domElement);

    // FIX: In-Game Lighting Model (Bright, Vibrant, matches game)
    const hemiLight = new THREE.HemisphereLight(0xffffff, 0x444444, 0.8);
    hemiLight.position.set(0, 20, 0);
    this.scene.add(hemiLight);
    const dirLight = new THREE.DirectionalLight(0xffffff, 0.6);
    dirLight.position.set(10, 20, 20);
    this.scene.add(dirLight);
    const backLight = new THREE.DirectionalLight(0xffffff, 0.4);
    backLight.position.set(-5, 5, -10);
    this.scene.add(backLight);

    if (this.options.showGrid) {
      this.scene.add(new THREE.GridHelper(2, 10, 0x444444, 0x333333));
    }

    this.character = new THREE.Group();
    this.character.rotation.y = Math.PI; // Face camera
    this.scene.add(this.character);

    this.textureLoader = new THREE.TextureLoader();

    // Initialize OIT renderer if enabled and DepthPeelingRenderer is available
    if (this.options.useOIT && typeof DepthPeelingRenderer !== 'undefined') {
      this.oitRenderer = new DepthPeelingRenderer(this.renderer, this.scene, this.camera, {
        layers: this.options.oitLayers
      });
      console.log('[OIT] Depth peeling enabled with', this.options.oitLayers, 'layers');
    } else if (this.options.useOIT) {
      console.warn('[OIT] DepthPeelingRenderer not available, falling back to standard rendering');
    }

    // Setup resize handler
    this._resizeHandler = () => {
      const w = this.container.clientWidth;
      const h = this.container.clientHeight;
      this.camera.aspect = w / h;
      this.camera.updateProjectionMatrix();
      this.renderer.setSize(w, h);
      // Also resize OIT renderer
      if (this.oitRenderer) {
        this.oitRenderer.resize(w, h);
      }
    };
    window.addEventListener('resize', this._resizeHandler);

    // Setup mouse/touch controls
    this._setupControls();

    return this;
  }

  _setupControls() {
    let isDragging = false;
    let prevX = 0;

    this.container.addEventListener('mousedown', (e) => {
      isDragging = true;
      prevX = e.clientX;
      this.autoRotate = false;
    });

    window.addEventListener('mouseup', () => isDragging = false);

    window.addEventListener('mousemove', (e) => {
      if (isDragging) {
        this.character.rotation.y += (e.clientX - prevX) * 0.01;
        prevX = e.clientX;
      }
    });

    this.container.addEventListener('touchstart', (e) => {
      isDragging = true;
      prevX = e.touches[0].clientX;
      this.autoRotate = false;
    });

    this.container.addEventListener('touchend', () => isDragging = false);

    this.container.addEventListener('touchmove', (e) => {
      if (isDragging) {
        this.character.rotation.y += (e.touches[0].clientX - prevX) * 0.01;
        prevX = e.touches[0].clientX;
      }
    });
  }

  // Load avatar by UUID
  async loadAvatar(uuid) {
    try {
      this.onLoadProgress('Fetching skin data...');
      // Add cache-busting timestamp to prevent browser caching
      const response = await fetch(`/avatar/${uuid}/model?_=${Date.now()}`);
      if (!response.ok) throw new Error('Failed to load avatar data');
      this.modelData = await response.json();
      if (this.modelData.error) throw new Error(this.modelData.error);

      // Determine hidden parts
      this._determineHiddenParts(this.modelData);

      this.onLoadProgress('Building character...');
      await this._buildCharacter(this.modelData);

      // Load default animation
      this.onLoadProgress('Loading animation...');
      this.currentAnimation = await this._loadAnimation(this.currentAnimationPath);

      if (!this._animating) {
        this._animating = true;
        this.lastFrameTime = performance.now();
        this._animate();
      }

      this.onLoadComplete(this.modelData);
    } catch (err) {
      this.onError(err);
      throw err;
    }
  }

  // Load avatar from direct skin data (for customizer preview)
  async loadFromSkinData(skinData) {
    try {
      this.onLoadProgress('Building character...');

      // Clear existing character
      this._clearCharacter();
      this.originalTransforms.clear();
      this.hiddenBodyParts.clear();
      this.polygonOffsetParts.clear();

      // Build model data structure
      this.modelData = {
        skinTone: skinData.skinTone || '01',
        bodyType: skinData.bodyType || 'Regular',
        parts: {}
      };

      // This would need server-side resolution of parts
      // For now we'll reload from server

      this._determineHiddenParts(this.modelData);
      await this._buildCharacter(this.modelData);

      this.onLoadComplete(this.modelData);
    } catch (err) {
      this.onError(err);
      throw err;
    }
  }

  // Load avatar from pre-resolved model data (for mock skins / debug testing)
  async loadFromModelData(modelData) {
    try {
      this.onLoadProgress('Building character from model data...');

      // Clear existing character
      this._clearCharacter();
      this.originalTransforms.clear();
      this.hiddenBodyParts.clear();
      this.polygonOffsetParts.clear();

      // Use provided model data directly
      this.modelData = modelData;

      this._determineHiddenParts(this.modelData);
      await this._buildCharacter(this.modelData);

      // Start animation loop if not already running
      if (!this._animating) {
        this._animating = true;
        this.lastFrameTime = performance.now();
        this._animate();
      }

      this.onLoadComplete(this.modelData);
    } catch (err) {
      this.onError(err);
      throw err;
    }
  }

  // Animation methods
  async setAnimation(animPath) {
    this.currentAnimationPath = animPath;
    this.animationTime = 0;

    if (!animPath) {
      this.currentAnimation = null;
      this._resetToOriginalPose();
      return;
    }

    this.currentAnimation = await this._loadAnimation(animPath);
  }

  setAutoRotate(enabled) {
    this.autoRotate = enabled;
  }

  setWireframe(enabled) {
    this.wireframeMode = enabled;
    this.character.traverse((c) => {
      if (c.isMesh) c.material.wireframe = enabled;
    });
  }

  resetView() {
    this.character.rotation.y = Math.PI;
  }

  rotate(amount) {
    this.character.rotation.y += amount;
  }

  _clearCharacter() {
    const cachedTextures = new Set(this.textureCache.values());
    const disposedTextures = new Set();
    this.character.traverse((object) => {
      if (!object.isMesh) return;
      object.geometry?.dispose();
      const materials = Array.isArray(object.material) ? object.material : [object.material];
      for (const material of materials) {
        if (material?.map && !cachedTextures.has(material.map) && !disposedTextures.has(material.map)) {
          material.map.dispose();
          disposedTextures.add(material.map);
        }
        material?.dispose();
      }
    });
    this.character.clear();
    this.originalTransforms.clear();
    this.hiddenBodyParts.clear();
    this.polygonOffsetParts.clear();
  }

  // Cleanup
  destroy() {
    this._destroyed = true;
    this._animating = false;
    if (this._animationFrame !== undefined) cancelAnimationFrame(this._animationFrame);
    this._clearCharacter();
    window.removeEventListener('resize', this._resizeHandler);
    if (this.oitRenderer) {
      this.oitRenderer.dispose();
      this.oitRenderer = null;
    }
    if (this.renderer) {
      this.renderer.dispose();
      this.container.removeChild(this.renderer.domElement);
    }
    for (const texture of this.textureCache.values()) texture.dispose();
    this.textureCache.clear();
  }

  // Private methods

  _determineHiddenParts(data) {
    if (data.parts?.pants || data.parts?.overpants) {
      // Shorts leave skin exposed: clothing never replaces whole leg meshes.
      for (const name of ['Pelvis', 'L-Thigh', 'R-Thigh', 'L-Calf', 'R-Calf']) {
        this.polygonOffsetParts.add(name);
      }
    }
    if (data.parts?.overtop || data.parts?.undertop) {
      this.hiddenBodyParts.add('Belly');
      this.hiddenBodyParts.add('Chest');
      this.polygonOffsetParts.add('L-Arm');
      this.polygonOffsetParts.add('R-Arm');
      this.polygonOffsetParts.add('L-Forearm');
      this.polygonOffsetParts.add('R-Forearm');
    }
    if (data.parts?.shoes) {
      this.hiddenBodyParts.add('L-Foot');
      this.hiddenBodyParts.add('R-Foot');
    }
    // Note: 'HeadTop' and 'HairBase' don't exist in Player.blockymodel
    // Haircuts are separate cosmetic models that attach to the Head bone
  }

  async _loadAnimation(animPath) {
    if (!animPath) return null;
    try {
      const response = await fetch('/asset/Common/Characters/Animations/' + animPath + '.blockyanim');
      if (!response.ok) return null;
      return await response.json();
    } catch (e) {
      console.error('[ANIMATION] Failed to load:', animPath, e);
      return null;
    }
  }

  _resetToOriginalPose() {
    this.originalTransforms.forEach((transform, nodeName) => {
      const node = this.character?.getObjectByName(nodeName);
      if (node) {
        node.position.copy(transform.position);
        node.quaternion.copy(transform.quaternion);
      }
    });
  }

  _animate() {
    if (this._destroyed) return;
    this._animationFrame = requestAnimationFrame(() => this._animate());

    const now = performance.now();
    const deltaTime = (now - this.lastFrameTime) / 1000;
    this.lastFrameTime = now;

    if (this.animationEnabled && this.currentAnimation) {
      const clampedDelta = Math.min(deltaTime, 0.1);
      this.animationTime += clampedDelta * FPS;
      if (this.currentAnimation.holdLastKeyframe) {
        this.animationTime = Math.min(this.animationTime, this.currentAnimation.duration);
      }
      this._applyAnimation(this.currentAnimation, this.animationTime);
    }

    if (this.autoRotate) {
      this.character.rotation.y += 0.005;
    }

    // Use OIT renderer if available, otherwise standard render
    if (this.oitRenderer) {
      this.oitRenderer.render();
    } else {
      this.renderer.render(this.scene, this.camera);
    }
  }

  _applyAnimation(animation, time) {
    if (!animation || !animation.nodeAnimations || !this.character) return;

    const duration = animation.duration;
    const loopTime = ((time % duration) + duration) % duration;

    for (const [nodeName, nodeAnim] of Object.entries(animation.nodeAnimations)) {
      const node = this.character.getObjectByName(nodeName);
      if (!node) continue;

      if (!this.originalTransforms.has(nodeName)) {
        this.originalTransforms.set(nodeName, {
          position: node.position.clone(),
          quaternion: node.quaternion.clone()
        });
      }
      const original = this.originalTransforms.get(nodeName);

      if (nodeAnim.orientation && nodeAnim.orientation.length > 0) {
        const animQuat = this._interpolateQuaternion(nodeAnim.orientation, loopTime, duration);
        if (animQuat) {
          node.quaternion.copy(original.quaternion);
          node.quaternion.multiply(animQuat);
        }
      }

      if (nodeAnim.position && nodeAnim.position.length > 0) {
        const animPos = this._interpolatePosition(nodeAnim.position, loopTime, duration);
        if (animPos) {
          node.position.copy(original.position);
          node.position.add(animPos);
        }
      }
    }
  }

  _findKeyframes(keyframes, time, duration) {
    if (!keyframes || keyframes.length === 0) return null;
    if (keyframes.length === 1) {
      return { k1: keyframes[0], k2: keyframes[0], t: 0 };
    }

    const sorted = [...keyframes].sort((a, b) => a.time - b.time);

    let idx1 = -1;
    for (let i = 0; i < sorted.length; i++) {
      if (sorted[i].time <= time) idx1 = i;
      else break;
    }

    if (idx1 === -1) {
      const k1 = sorted[sorted.length - 1];
      const k2 = sorted[0];
      const t1 = k1.time;
      const t2 = k2.time + duration;
      const currentTime = time + duration;
      let t = (t2 - t1) > 0 ? (currentTime - t1) / (t2 - t1) : 0;
      t = Math.max(0, Math.min(1, t));
      return { k1, k2, t, smooth: k1.interpolationType === 'smooth' };
    }

    if (idx1 === sorted.length - 1) {
      const k1 = sorted[idx1];
      const k2 = sorted[0];
      const timeSpan = (duration - k1.time) + k2.time;
      const elapsed = time - k1.time;
      let t = timeSpan > 0 ? elapsed / timeSpan : 0;
      t = Math.max(0, Math.min(1, t));
      return { k1, k2, t, smooth: k1.interpolationType === 'smooth' };
    }

    const k1 = sorted[idx1];
    const k2 = sorted[idx1 + 1];
    const timeSpan = k2.time - k1.time;
    let t = timeSpan > 0 ? (time - k1.time) / timeSpan : 0;
    t = Math.max(0, Math.min(1, t));
    return { k1, k2, t, smooth: k1.interpolationType === 'smooth' };
  }

  _interpolateQuaternion(keyframes, time, duration) {
    const kf = this._findKeyframes(keyframes, time, duration);
    if (!kf) return null;

    const { k1, k2, smooth } = kf;
    let t = kf.t;

    const q1 = new THREE.Quaternion(k1.delta.x, k1.delta.y, k1.delta.z, k1.delta.w);
    const q2 = new THREE.Quaternion(k2.delta.x, k2.delta.y, k2.delta.z, k2.delta.w);

    if (smooth) t = t * t * (3 - 2 * t);

    const result = new THREE.Quaternion();
    result.slerpQuaternions(q1, q2, t);
    return result;
  }

  _interpolatePosition(keyframes, time, duration) {
    const kf = this._findKeyframes(keyframes, time, duration);
    if (!kf) return null;

    const { k1, k2, smooth } = kf;
    let t = kf.t;

    if (smooth) t = t * t * (3 - 2 * t);

    const v1 = new THREE.Vector3(k1.delta.x, k1.delta.y, k1.delta.z);
    const v2 = new THREE.Vector3(k2.delta.x, k2.delta.y, k2.delta.z);
    const result = new THREE.Vector3();
    result.lerpVectors(v1, v2, t);
    result.multiplyScalar(SCALE);
    return result;
  }

  // Texture loading
  async _loadTexture(path) {
    if (this.textureCache.has(path)) return this.textureCache.get(path);

    // Try multiple path variations to find the texture
    const pathsToTry = [
      path.startsWith('Common/') ? '/asset/' + path : '/asset/Common/' + path,
      '/asset/' + path,
      '/asset/' + path.replace('Common/', ''),
      '/asset/Common/' + path.replace('Common/', '')
    ];

    // Remove duplicates
    const uniquePaths = [...new Set(pathsToTry)];

    console.log('[TEXTURE] Loading:', path, 'trying paths:', uniquePaths);

    for (const tryPath of uniquePaths) {
      try {
        const texture = await new Promise((resolve, reject) => {
          this.textureLoader.load(tryPath,
            (tex) => {
              console.log('[TEXTURE] Loaded successfully:', tryPath, 'size:', tex.image?.width, 'x', tex.image?.height);
              tex.magFilter = THREE.NearestFilter;
              tex.minFilter = THREE.NearestFilter;
              tex.wrapS = THREE.ClampToEdgeWrapping;
              tex.wrapT = THREE.ClampToEdgeWrapping;
              tex.generateMipmaps = false;
              resolve(tex);
            },
            undefined,
            (err) => {
              console.log('[TEXTURE] Failed path:', tryPath);
              reject(new Error('Failed to load'));
            }
          );
        });
        this.textureCache.set(path, texture);
        return texture;
      } catch (e) {
        // Try next path
      }
    }

    console.warn('[TEXTURE] Failed to load:', path, 'tried:', uniquePaths);
    return null;
  }

  _createCanvasTexture(canvas) {
    const texture = new THREE.CanvasTexture(canvas);
    texture.magFilter = THREE.NearestFilter;
    texture.minFilter = THREE.NearestFilter;
    texture.wrapS = THREE.ClampToEdgeWrapping;
    texture.wrapT = THREE.ClampToEdgeWrapping;
    texture.generateMipmaps = false;
    texture.userData = { width: canvas.width, height: canvas.height };
    return texture;
  }

  async _loadGradientData(gradientTexturePath) {
    if (!gradientTexturePath) return null;
    try {
      const gradientTexture = await this._loadTexture(gradientTexturePath);
      if (gradientTexture && gradientTexture.image) {
        const canvas = document.createElement('canvas');
        canvas.width = gradientTexture.image.width;
        canvas.height = gradientTexture.image.height;
        const ctx = canvas.getContext('2d');
        ctx.drawImage(gradientTexture.image, 0, 0);
        return ctx.getImageData(0, 0, canvas.width, canvas.height);
      }
    } catch (e) {
      console.error('[TINT] Gradient load error:', e);
    }
    return null;
  }

  async _createTintedTexture(greyscalePath, baseColor, gradientTexturePath = null) {
    const texture = await this._loadTexture(greyscalePath);
    if (!texture || !texture.image) return null;

    const canvas = document.createElement('canvas');
    const img = texture.image;
    canvas.width = img.width;
    canvas.height = img.height;

    const ctx = canvas.getContext('2d');
    ctx.drawImage(img, 0, 0);

    const imageData = ctx.getImageData(0, 0, canvas.width, canvas.height);
    const data = imageData.data;
    const gradientData = await this._loadGradientData(gradientTexturePath);
    const color = this._parseColor(baseColor);

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
            const gradX = Math.min(grey, gradientData.width - 1);
            const gradIdx = gradX * 4;
            r = gradientData.data[gradIdx];
            g = gradientData.data[gradIdx + 1];
            b = gradientData.data[gradIdx + 2];
          } else if (color) {
            // Simple tinting: greyscale modulates color brightness
            const t = grey / 255;
            r = Math.round(color.r * t);
            g = Math.round(color.g * t);
            b = Math.round(color.b * t);
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
    return this._createCanvasTexture(canvas);
  }



  _parseColor(color) {
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
      return this._parseColor(color[0]);
    }
    return { r: 200, g: 200, b: 200 };
  }

  // Character building
  async _buildCharacter(data) {
    const skinColor = this._getSkinToneColor(data.skinTone);
    const skinColorHex = '#' + skinColor.toString(16).padStart(6, '0');
    const skinToneGradient = this._getSkinToneGradientPath(data.skinTone);

    // Load base player model
    this.onLoadProgress('Loading player model...');
    try {
      const bodyPart = data.body || data.parts?.bodyCharacteristic;
      const bodyModelPath = bodyPart?.model || 'Characters/Player.blockymodel';
      const playerModel = await this._fetchModel(bodyModelPath.startsWith('Common/') ? bodyModelPath : 'Common/' + bodyModelPath);
      if (playerModel) {
        const bodyTexturePath = bodyPart?.greyscaleTexture || (data.bodyType === 'Muscular'
          ? 'Characters/Player_Textures/Player_Muscular_Greyscale.png'
          : 'Characters/Player_Textures/Player_Greyscale.png');

        const bodyTexture = await this._createTintedTexture(bodyTexturePath, skinColorHex, skinToneGradient);
        await this._renderPlayerModel(playerModel.nodes, this.character, skinColor, bodyTexture);
      }
    } catch (e) {
      console.error('[AVATAR] Could not load player model:', e);
    }

    // Cosmetics render order - order matters for layering
    // facialHair must come AFTER face with higher zOffset to render in front
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
      const part = data.parts?.[key];
      if (part && part.model) {
        this.onLoadProgress(`Loading ${key}...`);

        let color = this._getPartColor(part);
        if (!color) {
          if (['face', 'ears'].includes(key)) {
            color = skinColor;
          } else {
            color = this._getDefaultColor(key, skinColor);
          }
        }

        let texture = null;
        const isSkinPart = part.gradientSet === 'Skin' || ['face', 'ears', 'mouth'].includes(key);

        console.log(`[COSMETIC] ${key}:`, { texture: part.texture, greyscaleTexture: part.greyscaleTexture, gradientTexture: part.gradientTexture });

        if (part.texture) {
          texture = await this._loadTexture(part.texture);
          console.log(`[COSMETIC] ${key} loaded texture:`, texture ? 'SUCCESS' : 'FAILED');
        } else if (part.greyscaleTexture) {
          let gradientPath = part.gradientTexture;
          let baseCol = part.baseColor;

          if (isSkinPart) {
            gradientPath = skinToneGradient;
            baseCol = skinColorHex;
            color = skinColor;
          }

          if (!gradientPath && !baseCol) {
            baseCol = '#' + this._getDefaultColor(key, skinColor).toString(16).padStart(6, '0');
          }

          texture = await this._createTintedTexture(part.greyscaleTexture, baseCol, gradientPath);
        }

        if ((part.texture || part.greyscaleTexture) && !texture) {
          console.warn(`[COSMETIC] Skipping ${key}: its texture could not be loaded`);
          continue;
        }

        try {
          let modelPath = part.model;
          if (!modelPath.startsWith('Common/')) modelPath = 'Common/' + modelPath;
          const model = await this._fetchModel(modelPath);
          if (model) {
            await this._renderCosmeticModel(model.nodes, this.character, color, key, texture, 0);
          }
        } catch (e) {
          console.error(`[COSMETIC] ${key} error:`, e);
        }
      }
    }
  }

  async _fetchModel(path) {
    const response = await fetch('/asset/' + path);
    if (!response.ok) return null;
    return response.json();
  }

  async _renderPlayerModel(nodes, parent, skinColor, bodyTexture) {
    if (!nodes) return;
    for (const node of nodes) {
      this._renderPlayerNode(node, parent, skinColor, bodyTexture, 0);
    }
  }

  _renderPlayerNode(node, parent, skinColor, bodyTexture, depth = 0) {
    const nodeName = node.name || node.id || '';

    if (this.hiddenBodyParts.has(nodeName)) {
      const group = new THREE.Group();
      group.name = nodeName;
      this._applyTransform(group, node);
      parent.add(group);
      if (node.children) {
        for (const child of node.children) {
          this._renderPlayerNode(child, group, skinColor, bodyTexture, depth + 1);
        }
      }
      return;
    }

    const group = new THREE.Group();
    group.name = nodeName;
    this._applyTransform(group, node);

    const usePolygonOffset = this.polygonOffsetParts.has(nodeName);
    if (node.shape && node.shape.visible !== false && node.shape.type === 'box') {
      const mesh = this._createBoxMesh(node.shape, skinColor, nodeName, bodyTexture, usePolygonOffset);
      if (mesh) group.add(mesh);
    } else if (node.shape && node.shape.type === 'quad') {
      const mesh = this._createQuadMesh(node.shape, skinColor, nodeName, bodyTexture);
      if (mesh) group.add(mesh);
    }

    parent.add(group);

    if (node.children) {
      for (const child of node.children) {
        this._renderPlayerNode(child, group, skinColor, bodyTexture, depth + 1);
      }
    }
  }

  async _renderCosmeticModel(nodes, parent, color, partType, texture, zOffset) {
    if (!nodes) return;
    for (const node of nodes) {
      this._renderCosmeticNode(node, parent, color, partType, texture, zOffset, 0);
    }
  }

  _findBoneByName(parent, name) {
    if (!name) return null;
    let found = null;
    parent.traverse((obj) => {
      if (obj.name === name && !found) found = obj;
    });
    return found;
  }

  _renderCosmeticNode(node, parent, color, partType, texture, zOffset, depth = 0) {
    const nodeName = node.name || node.id || '';

    let targetParent = parent;
    let attachedToPlayerBone = false;
    if (nodeName && node.shape?.settings?.isPiece === true) {
      const matchingBone = this._findBoneByName(this.character, nodeName);
      if (matchingBone) {
        targetParent = matchingBone;
        attachedToPlayerBone = true;
      }
    }

    const group = new THREE.Group();
    group.name = nodeName + '_cosmetic';

    if (attachedToPlayerBone) {
      // A piece root uses the existing bone transform, not its template pose.
      group.quaternion.identity();
    } else {
      this._applyTransform(group, node);
    }

    // Debug logging for cape nodes
    if (partType === 'cape' && (nodeName === 'CapeArmor' || nodeName === 'Collar' || nodeName === 'Chest')) {
      console.log(`[CAPE DEBUG] ${nodeName}: attached=${attachedToPlayerBone}, parent=${parent.name}`);
      console.log(`  node.position: (${node.position?.x?.toFixed(2) || 0}, ${node.position?.y?.toFixed(2) || 0}, ${node.position?.z?.toFixed(2) || 0})`);
      console.log(`  node.orientation: (${(node.orientation?.x ?? 0).toFixed(3)}, ${(node.orientation?.y ?? 0).toFixed(3)}, ${(node.orientation?.z ?? 0).toFixed(3)}, ${(node.orientation?.w ?? 1).toFixed(3)})`);
      console.log(`  group.position: (${group.position.x.toFixed(3)}, ${group.position.y.toFixed(3)}, ${group.position.z.toFixed(3)})`);
      console.log(`  group.quaternion: (${group.quaternion.x.toFixed(3)}, ${group.quaternion.y.toFixed(3)}, ${group.quaternion.z.toFixed(3)}, ${group.quaternion.w.toFixed(3)})`);
      if (node.shape?.offset) {
        console.log(`  shape.offset: (${node.shape.offset.x?.toFixed(2) || 0}, ${node.shape.offset.y?.toFixed(2) || 0}, ${node.shape.offset.z?.toFixed(2) || 0})`);
      }
    }

    if (zOffset) {
      group.position.z += zOffset;
    }

    if (node.shape && node.shape.visible !== false && node.shape.type !== 'none') {
      let mesh = null;
      if (node.shape.type === 'box') {
        mesh = this._createBoxMesh(node.shape, color, nodeName, texture);
      } else if (node.shape.type === 'quad') {
        // The shipped model texture contains the eye whites and pupil alpha.
        // Preserve both; do not synthesize a shadow in place of the whites.
        mesh = this._createQuadMesh(node.shape, color, nodeName, texture);

        if (mesh && partType === 'mouth') {
          mesh.renderOrder = 99;
        } else if (mesh && partType === 'face') {
          mesh.renderOrder = 98;
        }
      }
      if (mesh) group.add(mesh);
    }

    targetParent.add(group);

    if (node.children) {
      for (const child of node.children) {
        const childName = child.name || child.id || '';
        const childBone = this._findBoneByName(this.character, childName);
        if (childBone && child.shape?.settings?.isPiece === true) {
          this._renderCosmeticNode(child, childBone, color, partType, texture, zOffset, depth + 1);
        } else {
          this._renderCosmeticNode(child, group, color, partType, texture, 0, depth + 1);
        }
      }
    }
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

  _createBoxMesh(shape, color, nodeName, texture = null, usePolygonOffset = false) {
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

    const pixelW = settings.size.x;
    const pixelH = settings.size.y;
    const pixelD = settings.size.z;

    const hasTextureLayout = texture && shape.textureLayout && Object.keys(shape.textureLayout).length > 0;
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

    if (hasTextureLayout) {
      const texW = texture.image?.width || texture.userData?.width;
      const texH = texture.image?.height || texture.userData?.height;

      if (texW && texH) {
        const faceMap = ['right', 'left', 'top', 'bottom', 'front', 'back'];
        const uvAttr = geometry.getAttribute('uv');
        const uvArray = uvAttr.array;

        for (let faceIdx = 0; faceIdx < 6; faceIdx++) {
          const faceName = faceMap[faceIdx];
          const layout = shape.textureLayout[faceName];

          if (layout && layout.offset) {
            const angle = layout.angle || 0;

            // Get face dimensions in pixels based on face orientation
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

            // Calculate UV coordinates based on angle (matching Blockbench plugin logic)
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

            // Apply UV rotation by mapping result to correct vertex positions
            // Three.js BoxGeometry UV vertex order: bottom-left, bottom-right, top-left, top-right
            const baseIdx = faceIdx * 4 * 2;

            // Apply the rotation to the UV assignment based on angle
            if (angle === 90) {
              // Rotate 90 CW: swap axes
              uvArray[baseIdx + 0] = u1; uvArray[baseIdx + 1] = v2;
              uvArray[baseIdx + 2] = u1; uvArray[baseIdx + 3] = v1;
              uvArray[baseIdx + 4] = u2; uvArray[baseIdx + 5] = v2;
              uvArray[baseIdx + 6] = u2; uvArray[baseIdx + 7] = v1;
            } else if (angle === 180) {
              // Rotate 180: flip both
              uvArray[baseIdx + 0] = u2; uvArray[baseIdx + 1] = v2;
              uvArray[baseIdx + 2] = u1; uvArray[baseIdx + 3] = v2;
              uvArray[baseIdx + 4] = u2; uvArray[baseIdx + 5] = v1;
              uvArray[baseIdx + 6] = u1; uvArray[baseIdx + 7] = v1;
            } else if (angle === 270) {
              // Rotate 270 CW (90 CCW): swap axes opposite
              uvArray[baseIdx + 0] = u2; uvArray[baseIdx + 1] = v1;
              uvArray[baseIdx + 2] = u2; uvArray[baseIdx + 3] = v2;
              uvArray[baseIdx + 4] = u1; uvArray[baseIdx + 5] = v1;
              uvArray[baseIdx + 6] = u1; uvArray[baseIdx + 7] = v2;
            } else {
              // No rotation
              uvArray[baseIdx + 0] = u1; uvArray[baseIdx + 1] = v1;
              uvArray[baseIdx + 2] = u2; uvArray[baseIdx + 3] = v1;
              uvArray[baseIdx + 4] = u1; uvArray[baseIdx + 5] = v2;
              uvArray[baseIdx + 6] = u2; uvArray[baseIdx + 7] = v2;
            }
          }
        }
        uvAttr.needsUpdate = true;
      }
    }

    // Determine if we need double-sided rendering:
    // 1. Model explicitly requests it via doubleSided property
    // 2. Negative stretch values flip the geometry, which inverts face winding
    const modelDoubleSided = shape.doubleSided === true;
    const needsDoubleSide = modelDoubleSided || flipX || flipY || flipZ;

    let material;
    if (texture) {
      const isBodyPart = ['Neck', 'Head', 'Chest', 'Belly', 'Pelvis'].includes(nodeName) ||
                         nodeName.includes('Arm') || nodeName.includes('Leg') ||
                         nodeName.includes('Hand') || nodeName.includes('Foot') ||
                         nodeName.includes('Thigh') || nodeName.includes('Calf');
      material = new THREE.MeshLambertMaterial({
        map: texture,
        wireframe: this.wireframeMode,
        alphaTest: isBodyPart ? 0 : 0.1,
        transparent: !isBodyPart,
        side: needsDoubleSide ? THREE.DoubleSide : THREE.FrontSide,
        depthWrite: true,
        polygonOffset: usePolygonOffset,
        polygonOffsetFactor: usePolygonOffset ? 1 : 0,
        polygonOffsetUnits: usePolygonOffset ? 1 : 0
      });
    } else {
      material = new THREE.MeshLambertMaterial({
        color: color,
        wireframe: this.wireframeMode,
        side: needsDoubleSide ? THREE.DoubleSide : THREE.FrontSide,
        polygonOffset: usePolygonOffset,
        polygonOffsetFactor: usePolygonOffset ? 1 : 0,
        polygonOffsetUnits: usePolygonOffset ? 1 : 0
      });
    }

    const mesh = new THREE.Mesh(geometry, material);

    if (flipX) mesh.scale.x = -1;
    if (flipY) mesh.scale.y = -1;
    if (flipZ) mesh.scale.z = -1;

    return mesh;
  }

  _createQuadMesh(shape, color, nodeName, texture = null, renderOrder = 0) {
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

    const hasTextureLayout = texture && shape.textureLayout && shape.textureLayout.front;
    if (hasTextureLayout) {
      const texW = texture.image?.width || texture.userData?.width;
      const texH = texture.image?.height || texture.userData?.height;

      if (texW && texH) {
        const layout = shape.textureLayout.front;
        if (layout && layout.offset) {
          const angle = layout.angle || 0;

          // Match Blockbench plugin UV calculation for quads
          let uv_size = [pixelW, pixelH];
          let uv_mirror = [
            layout.mirror?.x ? -1 : 1,
            layout.mirror?.y ? -1 : 1
          ];
          const uv_offset = [layout.offset.x, layout.offset.y];

          // Calculate UV result based on angle (same logic as boxes)
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
            newUVs = new Float32Array([
              u1, v2,
              u1, v1,
              u2, v2,
              u2, v1
            ]);
          } else if (angle === 180) {
            newUVs = new Float32Array([
              u2, v2,
              u1, v2,
              u2, v1,
              u1, v1
            ]);
          } else if (angle === 270) {
            newUVs = new Float32Array([
              u2, v1,
              u2, v2,
              u1, v1,
              u1, v2
            ]);
          } else {
            newUVs = new Float32Array([
              u1, v1,
              u2, v1,
              u1, v2,
              u2, v2
            ]);
          }
          geometry.setAttribute('uv', new THREE.BufferAttribute(newUVs, 2));
        }
      }
    }

    let material;
    if (texture) {
      material = new THREE.MeshLambertMaterial({
        map: texture,
        wireframe: this.wireframeMode,
        alphaTest: 1 / 255,
        transparent: true,
        side: THREE.DoubleSide,
        depthWrite: true,
        depthTest: true
      });
    } else {
      material = new THREE.MeshLambertMaterial({
        color: color,
        wireframe: this.wireframeMode,
        side: THREE.DoubleSide
      });
    }

    const mesh = new THREE.Mesh(geometry, material);

    if (renderOrder !== 0) {
      mesh.renderOrder = renderOrder;
    }

    if (flipX) mesh.scale.x = -1;
    if (flipY) mesh.scale.y = -1;

    return mesh;
  }

  // Skin tone data
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
    const validTones = ['01','02','03','04','05','06','07','08','09','10','11','12','13','14','15','16','17','18','19','20','21','22','25','26','27','28','29','30','31','32','33','35','36','37','38','39','41','42','45','46','47','48','49','50','51','52'];
    if (validTones.includes(tone)) {
      return `TintGradients/Skin_Tones/${tone}.png`;
    }
    return 'TintGradients/Skin_Tones/01.png';
  }

  _getPartColor(part) {
    if (!part) return null;
    if (part.baseColor) {
      const bc = Array.isArray(part.baseColor) ? part.baseColor[0] : part.baseColor;
      if (typeof bc === 'string' && bc.startsWith('#')) {
        return parseInt(bc.slice(1), 16);
      }
    }
    return null;
  }

  _getDefaultColor(type, skinColor) {
    const defaults = {
      'haircut': 0x4a3728, 'facialHair': 0x4a3728, 'eyebrows': 0x4a3728,
      'pants': 0x2c3e50, 'overpants': 0x34495e,
      'undertop': 0x5dade2, 'overtop': 0x2980b9,
      'shoes': 0x1a1a1a, 'gloves': 0x8b4513,
      'face': skinColor, 'mouth': 0xc0392b, 'ears': skinColor,
      'eyes': 0x3498db, 'underwear': 0xecf0f1, 'cape': 0x8e44ad,
      'headAccessory': 0xf1c40f, 'faceAccessory': 0xbdc3c7, 'earAccessory': 0xf1c40f
    };
    return defaults[type] || 0x888888;
  }
}

// Export for use
if (typeof module !== 'undefined' && module.exports) {
  module.exports = HytaleAvatarViewer;
}
