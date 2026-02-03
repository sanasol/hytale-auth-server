/**
 * Debug routes for server-side rendering testing
 */

const storage = require('../services/storage');
const nativeRenderer = require('../services/nativeRenderer');
const { sendJson, sendHtml, sendBinary } = require('../utils/response');

// Test UUIDs for demo purposes
const TEST_PLAYERS = [
  { uuid: '03fbfdef-9c4a-4eef-bd10-63fa96427133', name: 'TestPlayer' },
  { uuid: 'a1b2c3d4-e5f6-7890-abcd-ef1234567890', name: 'DemoUser' },
  { uuid: 'deadbeef-1234-5678-9abc-def012345678', name: 'NativeTest' }
];

/**
 * Handle SSR debug page
 */
async function handleDebugSSR(req, res) {
  console.log('[Debug SSR] Rendering debug page...');

  let status = { available: false, error: 'Unknown', dependencies: {} };
  try {
    status = nativeRenderer.getStatus();
  } catch (err) {
    console.error('[Debug SSR] Error getting status:', err);
    status.error = err.message;
  }

  // Use test players for demo (async player loading can be added later)
  let playersWithSkins = TEST_PLAYERS.map(p => ({
    ...p,
    hasSkin: true
  }));

  console.log('[Debug SSR] Status:', status.available, 'Players:', playersWithSkins.length);

  const html = `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>SSR Debug - Native Renderer</title>
  <style>
    * { margin: 0; padding: 0; box-sizing: border-box; }
    body {
      font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
      background: linear-gradient(135deg, #0f0f1a 0%, #1a1a2e 100%);
      min-height: 100vh;
      color: #e0e0e0;
      padding: 20px;
    }
    .container { max-width: 1200px; margin: 0 auto; }
    h1 { color: #00d4ff; margin-bottom: 10px; font-size: 1.8em; }
    h2 { color: #888; margin: 20px 0 15px; font-size: 1.2em; border-bottom: 1px solid #333; padding-bottom: 8px; }
    .subtitle { color: #666; margin-bottom: 30px; }

    .status-card {
      background: rgba(255, 255, 255, 0.05);
      border: 1px solid rgba(255, 255, 255, 0.1);
      border-radius: 10px;
      padding: 20px;
      margin-bottom: 20px;
    }
    .status-row { display: flex; gap: 20px; flex-wrap: wrap; align-items: center; margin-bottom: 10px; }
    .status-item { display: flex; align-items: center; gap: 8px; }
    .status-dot { width: 10px; height: 10px; border-radius: 50%; }
    .status-dot.ok { background: #00ff88; box-shadow: 0 0 8px #00ff88; }
    .status-dot.error { background: #ff4444; box-shadow: 0 0 8px #ff4444; }
    .status-dot.warn { background: #ffaa00; box-shadow: 0 0 8px #ffaa00; }

    .deps-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(150px, 1fr)); gap: 10px; margin-top: 15px; }
    .dep-item { background: rgba(0,0,0,0.2); padding: 10px; border-radius: 6px; font-family: monospace; }

    .players-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); gap: 20px; }
    .player-card {
      background: rgba(255, 255, 255, 0.03);
      border: 1px solid rgba(255, 255, 255, 0.08);
      border-radius: 10px;
      padding: 15px;
    }
    .player-header { display: flex; align-items: center; gap: 15px; margin-bottom: 15px; }
    .player-avatar-container { position: relative; width: 80px; height: 80px; }
    .player-avatar {
      width: 80px;
      height: 80px;
      border-radius: 8px;
      background: rgba(0,0,0,0.3);
      display: flex;
      align-items: center;
      justify-content: center;
      font-size: 24px;
    }
    .player-avatar img { width: 100%; height: 100%; object-fit: contain; border-radius: 8px; }
    .player-info h3 { color: #fff; margin-bottom: 5px; }
    .player-uuid { font-family: monospace; font-size: 0.75em; color: #666; word-break: break-all; }

    .render-buttons { display: flex; gap: 8px; flex-wrap: wrap; margin-bottom: 10px; }
    .render-btn {
      background: rgba(0, 212, 255, 0.2);
      border: 1px solid rgba(0, 212, 255, 0.3);
      color: #00d4ff;
      padding: 8px 15px;
      border-radius: 5px;
      cursor: pointer;
      font-size: 0.85em;
      transition: all 0.2s;
    }
    .render-btn:hover { background: rgba(0, 212, 255, 0.3); }
    .render-btn:disabled { opacity: 0.5; cursor: not-allowed; }
    .render-btn.browser { background: rgba(255, 170, 0, 0.2); border-color: rgba(255, 170, 0, 0.3); color: #ffaa00; }

    .render-result {
      margin-top: 15px;
      padding: 10px;
      background: rgba(0,0,0,0.2);
      border-radius: 6px;
      min-height: 100px;
      display: flex;
      flex-direction: column;
      gap: 10px;
    }
    .render-result img { max-width: 200px; max-height: 200px; image-rendering: pixelated; border: 1px solid #333; }
    .timing-info { font-size: 0.8em; color: #888; font-family: monospace; }
    .timing-item { display: flex; justify-content: space-between; padding: 2px 0; }
    .timing-value { color: #00d4ff; }

    .comparison-container { display: flex; gap: 20px; align-items: flex-start; flex-wrap: wrap; }
    .comparison-box { text-align: center; }
    .comparison-box label { display: block; margin-bottom: 5px; color: #888; font-size: 0.85em; }

    .error-msg { color: #ff6666; background: rgba(255, 100, 100, 0.1); padding: 10px; border-radius: 5px; }
    .loading { color: #888; font-style: italic; }

    .custom-uuid { margin-top: 20px; }
    .custom-uuid input {
      background: rgba(255,255,255,0.05);
      border: 1px solid rgba(255,255,255,0.1);
      color: #fff;
      padding: 10px 15px;
      border-radius: 5px;
      width: 350px;
      font-family: monospace;
    }
    .custom-uuid button { margin-left: 10px; }

    .info-section { margin-top: 30px; padding: 20px; background: rgba(0,0,0,0.2); border-radius: 10px; }
    .info-section h3 { color: #00d4ff; margin-bottom: 15px; }
    .info-section code { background: rgba(255,255,255,0.1); padding: 2px 6px; border-radius: 3px; font-size: 0.9em; }
    .info-section ul { margin-left: 20px; line-height: 1.8; }
  </style>
</head>
<body>
  <div class="container">
    <h1>SSR Debug - Native Renderer</h1>
    <p class="subtitle">Server-side avatar rendering using headless-gl + Three.js</p>

    <div class="status-card">
      <h2 style="margin-top: 0;">Renderer Status</h2>
      <div class="status-row">
        <div class="status-item">
          <div class="status-dot ${status.available ? 'ok' : 'error'}"></div>
          <span>Native Renderer: <strong>${status.available ? 'Available' : 'Not Available'}</strong></span>
        </div>
        ${status.error ? `<div class="error-msg">Error: ${status.error}</div>` : ''}
      </div>

      <div class="deps-grid">
        ${Object.entries(status.dependencies).map(([dep, loaded]) => `
          <div class="dep-item">
            <span class="status-dot ${loaded ? 'ok' : 'error'}" style="display: inline-block; width: 8px; height: 8px; margin-right: 8px;"></span>
            ${dep}: ${loaded ? 'loaded' : 'missing'}
          </div>
        `).join('')}
      </div>
    </div>

    <h2>Test Players (${playersWithSkins.length} with skins)</h2>
    <div class="players-grid">
      ${playersWithSkins.slice(0, 6).map(player => `
        <div class="player-card" data-uuid="${player.uuid}">
          <div class="player-header">
            <div class="player-avatar-container">
              <div class="player-avatar" id="avatar-${player.uuid}">?</div>
            </div>
            <div class="player-info">
              <h3>${player.name}</h3>
              <div class="player-uuid">${player.uuid}</div>
            </div>
          </div>

          <div class="render-buttons">
            <button class="render-btn" onclick="renderSSR('${player.uuid}', 'black')">SSR (black)</button>
            <button class="render-btn" onclick="renderSSR('${player.uuid}', 'transparent')">SSR (transparent)</button>
            <button class="render-btn browser" onclick="renderBrowser('${player.uuid}')">Browser</button>
            <button class="render-btn" onclick="compareRender('${player.uuid}')">Compare</button>
          </div>

          <div class="render-result" id="result-${player.uuid}">
            <span class="loading">Click a button to render...</span>
          </div>
        </div>
      `).join('')}

      ${playersWithSkins.length === 0 ? `
        <div class="player-card">
          <p style="color: #888;">No players with skins found. Create some test data first.</p>
        </div>
      ` : ''}
    </div>

    <div class="custom-uuid">
      <h2>Custom UUID Test</h2>
      <input type="text" id="custom-uuid" placeholder="Enter UUID...">
      <button class="render-btn" onclick="testCustomUuid()">Test SSR</button>
      <button class="render-btn browser" onclick="testCustomBrowser()">Test Browser</button>
      <div class="render-result" id="result-custom" style="margin-top: 15px;">
        <span class="loading">Enter a UUID and click Test...</span>
      </div>
    </div>

    <div class="info-section">
      <h3>API Endpoints</h3>
      <ul>
        <li><code>GET /debug/ssr</code> - This page</li>
        <li><code>GET /debug/ssr/render/{uuid}?bg=black|white|transparent</code> - Render head (SSR)</li>
        <li><code>GET /debug/ssr/status</code> - Renderer status JSON</li>
        <li><code>GET /debug/ssr/benchmark/{uuid}</code> - Performance benchmark</li>
      </ul>

      <h3 style="margin-top: 20px;">How it Works</h3>
      <ul>
        <li><strong>headless-gl</strong>: Native OpenGL bindings for Node.js (no GPU required)</li>
        <li><strong>Three.js</strong>: 3D rendering library (same code as browser)</li>
        <li><strong>sharp</strong>: Fast image processing for PNG output</li>
        <li><strong>node-canvas</strong>: Canvas implementation for texture loading</li>
      </ul>

      <h3 style="margin-top: 20px;">Benefits vs Puppeteer</h3>
      <ul>
        <li>~10x faster rendering (no browser overhead)</li>
        <li>~50x less memory (no Chrome process)</li>
        <li>Single process, no IPC overhead</li>
        <li>Better for batch rendering</li>
      </ul>
    </div>
  </div>

  <script>
    // SSR render
    async function renderSSR(uuid, bg = 'black') {
      const resultDiv = document.getElementById('result-' + uuid);
      resultDiv.innerHTML = '<span class="loading">Rendering server-side...</span>';

      try {
        const start = performance.now();
        const response = await fetch('/debug/ssr/render/' + uuid + '?bg=' + bg + '&format=json');
        const data = await response.json();
        const clientTime = performance.now() - start;

        if (data.error) {
          resultDiv.innerHTML = '<div class="error-msg">' + data.error + '</div>';
          return;
        }

        const timingsHtml = Object.entries(data.timings).map(([key, value]) =>
          '<div class="timing-item"><span>' + key + ':</span><span class="timing-value">' + value + 'ms</span></div>'
        ).join('');

        resultDiv.innerHTML = \`
          <div class="comparison-container">
            <div class="comparison-box">
              <label>SSR Result (bg=\${bg})</label>
              <img src="/debug/ssr/render/\${uuid}?bg=\${bg}&_t=\${Date.now()}" alt="SSR Head">
            </div>
          </div>
          <div class="timing-info">
            <strong>Timings:</strong>
            \${timingsHtml}
            <div class="timing-item"><span>network:</span><span class="timing-value">\${Math.round(clientTime)}ms</span></div>
          </div>
        \`;
      } catch (err) {
        resultDiv.innerHTML = '<div class="error-msg">Error: ' + err.message + '</div>';
      }
    }

    // Browser render (iframe)
    function renderBrowser(uuid) {
      const resultDiv = document.getElementById('result-' + uuid);
      resultDiv.innerHTML = \`
        <div class="comparison-container">
          <div class="comparison-box">
            <label>Browser Render</label>
            <iframe src="/avatar/\${uuid}/head?bg=black&nocache=1" width="200" height="200" style="border: 1px solid #333; border-radius: 8px;"></iframe>
          </div>
        </div>
        <div class="timing-info">
          <em>Browser renders via Three.js in iframe</em>
        </div>
      \`;
    }

    // Compare both
    async function compareRender(uuid) {
      const resultDiv = document.getElementById('result-' + uuid);
      resultDiv.innerHTML = '<span class="loading">Rendering both methods...</span>';

      try {
        const start = performance.now();
        const response = await fetch('/debug/ssr/render/' + uuid + '?bg=black&format=json');
        const data = await response.json();
        const ssrTime = performance.now() - start;

        if (data.error) {
          resultDiv.innerHTML = '<div class="error-msg">SSR Error: ' + data.error + '</div>';
          return;
        }

        const timingsHtml2 = Object.entries(data.timings).map(([key, value]) =>
          '<div class="timing-item"><span>' + key + ':</span><span class="timing-value">' + value + 'ms</span></div>'
        ).join('');

        resultDiv.innerHTML = \`
          <div class="comparison-container">
            <div class="comparison-box">
              <label>SSR (Server)</label>
              <img src="/debug/ssr/render/\${uuid}?bg=black&_t=\${Date.now()}" alt="SSR Head" style="background: #000;">
            </div>
            <div class="comparison-box">
              <label>Browser (Three.js)</label>
              <iframe src="/avatar/\${uuid}/head?bg=black&nocache=1" width="200" height="200" style="border: 1px solid #333; border-radius: 8px;"></iframe>
            </div>
          </div>
          <div class="timing-info">
            <strong>SSR Timings:</strong>
            \${timingsHtml2}
            <div class="timing-item"><span>network+total:</span><span class="timing-value">\${Math.round(ssrTime)}ms</span></div>
          </div>
        \`;
      } catch (err) {
        resultDiv.innerHTML = '<div class="error-msg">Error: ' + err.message + '</div>';
      }
    }

    // Custom UUID test
    function testCustomUuid() {
      const uuid = document.getElementById('custom-uuid').value.trim();
      if (!uuid) return alert('Enter a UUID');
      renderSSRCustom(uuid);
    }

    function testCustomBrowser() {
      const uuid = document.getElementById('custom-uuid').value.trim();
      if (!uuid) return alert('Enter a UUID');
      const resultDiv = document.getElementById('result-custom');
      resultDiv.innerHTML = \`
        <div class="comparison-container">
          <div class="comparison-box">
            <label>Browser Render</label>
            <iframe src="/avatar/\${uuid}/head?bg=black&nocache=1" width="200" height="200" style="border: 1px solid #333;"></iframe>
          </div>
        </div>
      \`;
    }

    async function renderSSRCustom(uuid) {
      const resultDiv = document.getElementById('result-custom');
      resultDiv.innerHTML = '<span class="loading">Rendering server-side...</span>';

      try {
        const start = performance.now();
        const response = await fetch('/debug/ssr/render/' + uuid + '?bg=black&format=json');
        const data = await response.json();
        const clientTime = performance.now() - start;

        if (data.error) {
          resultDiv.innerHTML = '<div class="error-msg">' + data.error + '</div>';
          return;
        }

        const timingsHtml3 = Object.entries(data.timings || {}).map(([key, value]) =>
          '<div class="timing-item"><span>' + key + ':</span><span class="timing-value">' + value + 'ms</span></div>'
        ).join('');

        resultDiv.innerHTML = \`
          <div class="comparison-container">
            <div class="comparison-box">
              <label>SSR Result</label>
              <img src="/debug/ssr/render/\${uuid}?bg=black&_t=\${Date.now()}" alt="SSR Head" style="background: #000;">
            </div>
          </div>
          <div class="timing-info">
            <strong>Timings:</strong>
            \${timingsHtml3}
          </div>
        \`;
      } catch (err) {
        resultDiv.innerHTML = '<div class="error-msg">Error: ' + err.message + '</div>';
      }
    }
  </script>
</body>
</html>`;

  sendHtml(res, 200, html);
}

/**
 * Handle SSR render endpoint
 */
async function handleDebugSSRRender(req, res, uuid) {
  const url = new URL(req.url, `http://${req.headers.host}`);
  const bg = url.searchParams.get('bg') || 'black';
  const format = url.searchParams.get('format') || 'image';
  const width = parseInt(url.searchParams.get('width') || '200', 10);
  const height = parseInt(url.searchParams.get('height') || '200', 10);

  try {
    const result = await nativeRenderer.renderHead(uuid, bg, width, height);

    if (format === 'json') {
      sendJson(res, 200, {
        success: true,
        uuid,
        bg,
        size: { width, height },
        timings: result.timings,
        bufferSize: result.buffer.length
      });
      return;
    }

    sendBinary(res, 200, result.buffer, 'image/png', {
      'Cache-Control': 'no-cache',
      'X-Render-Time': result.timings.total + 'ms'
    });
  } catch (err) {
    console.error('[Debug SSR] Render error:', err);
    sendJson(res, 500, {
      error: err.message,
      stack: process.env.NODE_ENV === 'development' ? err.stack : undefined
    });
  }
}

/**
 * Handle SSR status endpoint
 */
function handleDebugSSRStatus(req, res) {
  const status = nativeRenderer.getStatus();
  sendJson(res, 200, status);
}

/**
 * Handle SSR benchmark endpoint
 */
async function handleDebugSSRBenchmark(req, res, uuid) {
  const iterations = 5;
  const results = [];

  for (let i = 0; i < iterations; i++) {
    try {
      const result = await nativeRenderer.renderHead(uuid, 'black');
      results.push({
        iteration: i + 1,
        ...result.timings
      });
    } catch (err) {
      results.push({
        iteration: i + 1,
        error: err.message
      });
    }
  }

  // Calculate averages
  const successful = results.filter(r => !r.error);
  const averages = {};

  if (successful.length > 0) {
    const timingKeys = Object.keys(successful[0]).filter(k => k !== 'iteration');
    for (const key of timingKeys) {
      averages[key] = Math.round(successful.reduce((sum, r) => sum + r[key], 0) / successful.length);
    }
  }

  sendJson(res, 200, {
    uuid,
    iterations,
    results,
    averages,
    successRate: `${successful.length}/${iterations}`
  });
}

/**
 * Route handler for debug endpoints
 */
async function handleDebugRoutes(req, res, urlPath) {
  if (urlPath === '/debug/ssr' || urlPath === '/debug/ssr/') {
    await handleDebugSSR(req, res);
    return true;
  }

  if (urlPath === '/debug/ssr/status') {
    handleDebugSSRStatus(req, res);
    return true;
  }

  if (urlPath.startsWith('/debug/ssr/render/')) {
    const uuid = urlPath.replace('/debug/ssr/render/', '').split('?')[0];
    await handleDebugSSRRender(req, res, uuid);
    return true;
  }

  if (urlPath.startsWith('/debug/ssr/benchmark/')) {
    const uuid = urlPath.replace('/debug/ssr/benchmark/', '').split('?')[0];
    await handleDebugSSRBenchmark(req, res, uuid);
    return true;
  }

  return false;
}

module.exports = {
  handleDebugRoutes,
  handleDebugSSR,
  handleDebugSSRRender,
  handleDebugSSRStatus,
  handleDebugSSRBenchmark
};
