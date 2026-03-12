/**
 * Order-Independent Transparency (OIT) Renderer for Three.js
 *
 * Uses a simplified depth-sorted approach with proper alpha compositing.
 * Sorts transparent objects by distance to camera and renders back-to-front.
 *
 * Usage:
 *   const oitRenderer = new DepthPeelingRenderer(renderer, scene, camera);
 *   oitRenderer.render();
 */

class DepthPeelingRenderer {
  constructor(renderer, scene, camera, options = {}) {
    this.renderer = renderer;
    this.scene = scene;
    this.camera = camera;
    this.enabled = true;
    this.debug = options.debug || false;

    // Temporary vector for distance calculation
    this._tempVec = new THREE.Vector3();
    this._cameraWorldPos = new THREE.Vector3();
  }

  /**
   * Get object's distance to camera (for sorting)
   */
  _getDistanceToCamera(object) {
    object.getWorldPosition(this._tempVec);
    return this._tempVec.distanceTo(this._cameraWorldPos);
  }

  /**
   * Check if object should use OIT (is transparent)
   */
  _isTransparent(object) {
    if (!object.isMesh) return false;
    if (!object.material) return false;

    // Explicitly marked for OIT
    if (object.userData.oitTransparent === true) return true;

    const mat = object.material;

    // Has transparency enabled with actual alpha blending
    if (mat.transparent === true) {
      // Check if it has actual semi-transparency
      if (mat.opacity < 1) return true;
      // AlphaTest 0 means all alpha values pass - true transparency
      if (mat.alphaTest === 0) return true;
    }

    return false;
  }

  /**
   * Main render function
   */
  render() {
    if (!this.enabled) {
      this.renderer.render(this.scene, this.camera);
      return;
    }

    // Get camera world position for distance calculations
    this.camera.getWorldPosition(this._cameraWorldPos);

    // Collect and classify all meshes
    const opaqueMeshes = [];
    const transparentMeshes = [];

    this.scene.traverse((object) => {
      if (!object.isMesh || !object.visible) return;

      if (this._isTransparent(object)) {
        transparentMeshes.push({
          mesh: object,
          distance: this._getDistanceToCamera(object),
          originalRenderOrder: object.renderOrder
        });
      } else {
        opaqueMeshes.push(object);
      }
    });

    if (this.debug && transparentMeshes.length > 0) {
      console.log('[OIT] Transparent meshes:', transparentMeshes.length);
    }

    // If no transparent meshes, render normally
    if (transparentMeshes.length === 0) {
      this.renderer.render(this.scene, this.camera);
      return;
    }

    // Sort transparent meshes back-to-front (furthest first)
    transparentMeshes.sort((a, b) => b.distance - a.distance);

    // Assign render orders: opaque first (low numbers), then transparent back-to-front
    // Opaque meshes get render order 0
    for (const mesh of opaqueMeshes) {
      mesh.renderOrder = 0;
    }

    // Transparent meshes get ascending render orders (back-to-front means ascending)
    // Start at 1000 to ensure they render after opaque
    let renderOrder = 1000;
    for (const item of transparentMeshes) {
      item.mesh.renderOrder = renderOrder++;
    }

    // Configure transparent materials for proper blending
    for (const item of transparentMeshes) {
      const mat = item.mesh.material;
      // Ensure proper transparency settings
      mat.transparent = true;
      mat.depthWrite = false; // Don't write to depth buffer
      mat.depthTest = true;   // But still test against it
    }

    // Render the scene - Three.js will use render order
    this.renderer.render(this.scene, this.camera);

    // Restore original render orders
    for (const item of transparentMeshes) {
      item.mesh.renderOrder = item.originalRenderOrder;
    }
  }

  /**
   * Mark an object for OIT rendering
   */
  static markTransparent(object) {
    object.userData.oitTransparent = true;
  }

  /**
   * Unmark an object from OIT rendering
   */
  static unmarkTransparent(object) {
    object.userData.oitTransparent = false;
  }

  /**
   * Resize handler (no-op for this simple implementation)
   */
  resize(width, height) {
    // No render targets to resize in this simple implementation
  }

  dispose() {
    // Nothing to dispose in this simple implementation
  }
}

// Export for use
if (typeof module !== 'undefined' && module.exports) {
  module.exports = DepthPeelingRenderer;
}
