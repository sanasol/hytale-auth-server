(function (root) {
  'use strict';
  const MAX_CHARACTERS = 12;
  const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
  function eligiblePlayer(player) {
    return UUID_PATTERN.test(player.uuid || '') && !/^Server-/i.test(player.name || player.username || '');
  }
  function diversityKey(model) {
    const raw = model.raw || {};
    return ['bodyCharacteristic', 'face', 'eyes', 'headAccessory', 'faceAccessory', 'haircut', 'cape']
      .map(key => String(raw[key] || '').split('.')[0]).join('|');
  }
  function diverseSample(candidates, limit = MAX_CHARACTERS) {
    const remaining = [...new Map(candidates.map(candidate => [candidate.uuid, candidate])).values()];
    const features = candidate => ['bodyCharacteristic', 'face', 'eyes', 'headAccessory', 'faceAccessory', 'haircut', 'cape']
      .filter(key => candidate.model.raw?.[key]).map(key => key + ':' + String(candidate.model.raw[key]).split('.')[0]);
    const frequency = new Map(), covered = new Set(), selected = [];
    for (const candidate of remaining) for (const feature of features(candidate)) frequency.set(feature, (frequency.get(feature) || 0) + 1);
    while (remaining.length && selected.length < limit) {
      let best = 0, bestScore = -1;
      for (let index = 0; index < remaining.length; index++) {
        const score = features(remaining[index]).reduce((sum, feature) => sum + (covered.has(feature) ? 0 :
          (feature.startsWith('bodyCharacteristic:') ? 5 : 1) / frequency.get(feature)), 0);
        if (score > bestScore) { best = index; bestScore = score; }
      }
      const chosen = remaining.splice(best, 1)[0]; selected.push(chosen);
      for (const feature of features(chosen)) covered.add(feature);
    }
    return selected;
  }
  const exports = { eligiblePlayer, diverseSample, diversityKey, UUID_PATTERN, MAX_CHARACTERS };
  if (typeof module !== 'undefined' && module.exports) module.exports = exports;
  if (!root.document) return;

  const $ = id => document.getElementById(id);
  let viewer, token, characters = [], rendering = false, pending = false, busyLoading = false;
  let renderTimer;
  const animations = new Map();
  function status(message) { $('status').textContent = message; }
  async function adminFetch(path) {
    const response = await fetch(path, { headers: { 'X-Admin-Token': token || '' } });
    if (response.status === 401 || response.status === 403) { $('login').hidden = false; throw new Error('Admin sign-in required'); }
    if (!response.ok) throw new Error('Request failed: ' + response.status);
    return response.json();
  }
  async function fetchCharacter(player) {
    const response = await fetch('/avatar/' + encodeURIComponent(player.uuid) + '/model', { cache: 'no-store' });
    if (response.status === 404) return null;
    if (!response.ok) throw new Error('Model request failed: ' + response.status);
    const model = await response.json();
    if (!model.raw || !Object.values(model.raw).some(value => value)) return null;
    return { uuid: player.uuid, name: player.username || player.name || player.uuid, model };
  }
  async function loadSample() {
    if (busyLoading) return;
    busyLoading = true; $('refresh').disabled = true;
    try {
      const candidates = [];
      for (let page = 1; page <= 3 && candidates.length < 30; page++) {
        status('Loading player sample, page ' + page + '…');
        const data = await adminFetch('/admin/api/players?page=' + page + '&limit=50');
        for (const player of data.players || []) {
          if (!eligiblePlayer(player)) continue;
          const character = await fetchCharacter(player);
          if (character) candidates.push(character);
          if (candidates.length >= 30) break;
        }
        if (page >= (data.totalPages || 1)) break;
      }
      characters = diverseSample(candidates);
      await renderGallery();
      if (!characters.length) status('No saved character skins found in the sampled pages. Add a player UUID directly.');
    } catch (error) { status(error.message); }
    finally { busyLoading = false; $('refresh').disabled = false; }
  }

  function getViewer() {
    if (viewer) return viewer;
    class SnapshotViewer extends HytaleAvatarViewer {
      _animate() {} // Snapshot gallery has no RAF loop or per-row WebGL contexts.
      _setupControls() {}
      async _fetchModel(path) {
        const model = await super._fetchModel(path);
        if (!model) this.auditWarnings.push('Missing model: ' + path);
        return model;
      }
      async _loadTexture(path) {
        const texture = await super._loadTexture(path);
        if (!texture) this.auditWarnings.push('Missing texture: ' + path);
        return texture;
      }
    }
    viewer = new SnapshotViewer($('render-host'), { autoRotate: false, showGrid: false, useOIT: true, alpha: true });
    viewer.auditWarnings = [];
    viewer.init(); viewer.renderer.setPixelRatio(1); viewer.animationEnabled = false;
    return viewer;
  }
  function disposeCharacter(renderer) {
    renderer._clearCharacter();
    for (const texture of renderer.textureCache.values()) texture.dispose();
    renderer.textureCache.clear();
  }
  function makeCell(row) { const cell = document.createElement('td'); row.appendChild(cell); return cell; }
  function makeSnapshot(renderer, view, background) {
    renderer.character.rotation.y = 0;
    renderer.character.updateMatrixWorld(true);
    const head = renderer.character.getObjectByName('Head');
    const headMesh = head?.children.find(child => child.isMesh);
    const center = headMesh ? headMesh.getWorldPosition(new THREE.Vector3()) : new THREE.Vector3(0, 1.07, 0);
    const headBox = headMesh ? new THREE.Box3().setFromObject(headMesh) : null;
    const headWidth = headBox ? headBox.getSize(new THREE.Vector3()).x : 0.3;
    const distance = Math.max(0.55, headWidth * 2.3);
    const width = view === 'full' ? 192 : 128, height = view === 'full' ? 256 : 128;
    renderer.renderer.setSize(width, height);
    renderer.camera.aspect = width / height; renderer.camera.fov = 40;
    let target = center.clone(), position;
    if (view === 'portrait') {
      // Identical bind-pose framing to the server PNG for color/layer comparison.
      renderer.character.rotation.y = Math.PI;
      target = new THREE.Vector3(0, 1.0, 0);
      position = new THREE.Vector3(0, 1.1, -1.0);
    } else if (view === 'full') {
      const box = new THREE.Box3().setFromObject(renderer.character), size = box.getSize(new THREE.Vector3());
      target = box.getCenter(new THREE.Vector3());
      const distance = Math.max(size.y, size.x / renderer.camera.aspect) / (2 * Math.tan(Math.PI * renderer.camera.fov / 360)) * 1.18;
      position = target.clone().add(new THREE.Vector3(0, 0.04, distance));
    } else if (view === 'right') position = target.clone().add(new THREE.Vector3(distance, 0, 0));
    else if (view === 'back') position = target.clone().add(new THREE.Vector3(0, 0, -distance));
    else if (view === 'portrait') position = target.clone().add(new THREE.Vector3(distance * 0.32, 0.05, distance));
    else position = target.clone().add(new THREE.Vector3(0, 0, distance));
    renderer.camera.position.copy(position); renderer.camera.lookAt(target); renderer.camera.updateProjectionMatrix();
    const transparent = background === 'transparent';
    const color = background === 'white' ? 0xffffff : 0x181c30;
    renderer.scene.background = transparent ? null : new THREE.Color(color);
    renderer.renderer.setClearColor(color, transparent ? 0 : 1);
    if (renderer.oitRenderer) renderer.oitRenderer.render();
    else renderer.renderer.render(renderer.scene, renderer.camera);
    return { src: renderer.renderer.domElement.toDataURL('image/png'), width, height };
  }
  async function renderGallery() {
    if (rendering) { pending = true; return; }
    rendering = true;
    try {
      do {
        pending = false;
        const sample = characters.slice(), background = $('background').value;
        const animated = $('animate').checked, animationPath = $('animation').value, frame = Number($('frame').value);
        const table = document.createElement('table'), heading = document.createElement('tr');
        for (const text of ['Player / saved parts', 'Server head PNG', 'Browser head · 128px', 'Front', 'Right profile', 'Back', 'Full body']) {
          const cell = document.createElement('th'); cell.textContent = text; heading.appendChild(cell);
        }
        table.appendChild(heading); $('gallery').replaceChildren(table);
        const renderer = getViewer();
        let animation = null;
        if (animated) {
          if (!animations.has(animationPath)) animations.set(animationPath, await renderer._loadAnimation(animationPath));
          animation = animations.get(animationPath);
        }
        for (let index = 0; index < sample.length; index++) {
          const character = sample[index];
          status('Rendering ' + (index + 1) + '/' + sample.length + ': ' + character.name);
          const row = document.createElement('tr'), info = makeCell(row);
          const name = document.createElement('div'); name.className = 'name'; name.textContent = character.name; info.appendChild(name);
          const uuid = document.createElement('div'); uuid.className = 'uuid'; uuid.textContent = character.uuid; info.appendChild(uuid);
          const details = document.createElement('details'), summary = document.createElement('summary'), raw = document.createElement('pre');
          summary.textContent = 'Saved part IDs'; raw.textContent = JSON.stringify(character.model.raw, null, 2); details.append(summary, raw); info.appendChild(details);
          const open = document.createElement('a'); open.href = '/avatar/' + encodeURIComponent(character.uuid); open.textContent = 'Open viewer'; open.target = '_blank'; info.appendChild(open);
          const serverCell = makeCell(row), serverImage = document.createElement('img'), serverStatus = document.createElement('div');
          serverImage.width = 128; serverImage.height = 128; serverImage.alt = character.name + ': server head';
          if (background === 'transparent') serverImage.className = 'checker';
          serverImage.onerror = () => { serverImage.hidden = true; serverStatus.textContent = 'Server PNG unavailable'; };
          serverImage.src = '/avatar/' + encodeURIComponent(character.uuid) + '/head.png?bg=' +
            (background === 'dark' ? 'black' : background) + '&v=' + encodeURIComponent(document.querySelector('meta[name="avatar-render-version"]').content);
          serverStatus.className = 'caption'; serverStatus.textContent = 'Server renderer'; serverCell.append(serverImage, serverStatus);
          table.appendChild(row);
          try {
            disposeCharacter(renderer); renderer.auditWarnings = [];
            await renderer.loadFromModelData(character.model);
            if (animation) renderer._applyAnimation(animation, frame);
            if (animated && !animation) renderer.auditWarnings.push('Animation unavailable: ' + animationPath);
            for (const view of ['portrait', 'front', 'right', 'back', 'full']) {
              const imageData = makeSnapshot(renderer, view, background), cell = makeCell(row), image = document.createElement('img');
              image.src = imageData.src; image.width = imageData.width; image.height = imageData.height;
              image.alt = character.name + ': ' + view; if (background === 'transparent') image.className = 'checker'; cell.appendChild(image);
            }
            if (renderer.auditWarnings.length) { const warnings = document.createElement('p'); warnings.className = 'warnings'; warnings.textContent = [...new Set(renderer.auditWarnings)].join('\n'); info.appendChild(warnings); }
          } catch (error) { const warning = document.createElement('p'); warning.className = 'warnings'; warning.textContent = error.message; info.appendChild(warning); }
          if (pending) break; // Changed controls restart the queue with the latest pose.
        }
        disposeCharacter(renderer);
        status(sample.length + ' characters · one renderer · ' + (animated ? animationPath + ', frame ' + frame : 'bind pose'));
      } while (pending);
    } finally { rendering = false; }
  }
  function scheduleRender() { clearTimeout(renderTimer); renderTimer = setTimeout(() => renderGallery().catch(error => status(error.message)), 180); }
  $('refresh').addEventListener('click', loadSample);
  for (const id of ['background', 'animate', 'animation']) $(id).addEventListener('change', scheduleRender);
  $('frame').addEventListener('input', () => { $('frame-value').value = $('frame').value; scheduleRender(); });
  $('add-form').addEventListener('submit', async event => {
    event.preventDefault(); const uuid = $('uuid').value.trim();
    if (!UUID_PATTERN.test(uuid)) { status('Enter a valid player UUID.'); return; }
    $('add').disabled = true;
    try {
      const character = await fetchCharacter({ uuid });
      if (!character) { status('This UUID has no saved skin.'); return; }
      characters = characters.filter(item => item.uuid !== uuid).concat(character).slice(-MAX_CHARACTERS);
      await renderGallery();
    } catch (error) { status(error.message); }
    finally { $('add').disabled = false; }
  });
  window.addEventListener('pagehide', () => { if (viewer) viewer.destroy(); });
  (async () => {
    token = localStorage.getItem('adminToken');
    if (!token) { $('login').hidden = false; return; }
    try {
      const verified = await adminFetch('/admin/verify');
      if (!verified.valid) { $('login').hidden = false; return; }
      await loadSample();
    } catch (error) { status(error.message); }
  })();
})(typeof window !== 'undefined' ? window : globalThis);
