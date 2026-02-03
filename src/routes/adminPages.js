/**
 * Admin Pages - Separate full pages for each section
 */
const { sendHtml } = require('../utils/response');

// Shared HTML components
const sharedStyles = `
  * { box-sizing: border-box; margin: 0; padding: 0; }
  body {
    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
    background: linear-gradient(135deg, #1a1a2e 0%, #16213e 100%);
    color: #e0e0e0;
    min-height: 100vh;
  }
  .container { max-width: 1400px; margin: 0 auto; padding: 20px; }

  /* Navigation */
  .top-nav {
    background: rgba(0, 0, 0, 0.4);
    padding: 15px 20px;
    display: flex;
    align-items: center;
    gap: 20px;
    border-bottom: 1px solid rgba(255,255,255,0.1);
  }
  .top-nav .logo { color: #00d4ff; font-size: 1.3em; font-weight: bold; text-decoration: none; }
  .top-nav .nav-links { display: flex; gap: 5px; }
  .top-nav .nav-link {
    padding: 8px 16px;
    color: #888;
    text-decoration: none;
    border-radius: 5px;
    transition: all 0.2s;
  }
  .top-nav .nav-link:hover { background: rgba(255,255,255,0.1); color: #fff; }
  .top-nav .nav-link.active { background: rgba(0, 212, 255, 0.2); color: #00d4ff; }
  .top-nav .nav-right { margin-left: auto; display: flex; align-items: center; gap: 15px; }
  .top-nav .status { font-size: 0.85em; color: #888; }
  .top-nav .status-dot { width: 8px; height: 8px; border-radius: 50%; display: inline-block; margin-right: 5px; }
  .top-nav .status-dot.online { background: #00ff88; }
  .top-nav .status-dot.offline { background: #ff4444; }
  .logout-btn {
    background: rgba(255,100,100,0.2);
    border: 1px solid rgba(255,100,100,0.3);
    color: #ff6b6b;
    padding: 6px 12px;
    border-radius: 5px;
    cursor: pointer;
    font-size: 0.85em;
  }

  /* Stats bar */
  .stats-bar {
    display: flex;
    gap: 20px;
    padding: 15px 20px;
    background: rgba(0,0,0,0.2);
    margin-bottom: 20px;
    border-radius: 8px;
    flex-wrap: wrap;
  }
  .stat-item { text-align: center; }
  .stat-value { font-size: 1.5em; font-weight: bold; color: #00d4ff; }
  .stat-label { font-size: 0.8em; color: #888; }

  /* Cards */
  .card {
    background: rgba(255,255,255,0.03);
    border: 1px solid rgba(255,255,255,0.08);
    border-radius: 10px;
    padding: 20px;
    margin-bottom: 15px;
  }
  .card-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 15px;
    padding-bottom: 10px;
    border-bottom: 1px solid rgba(255,255,255,0.1);
  }
  .card-title { color: #00d4ff; font-size: 1.2em; }

  /* Server card */
  .server-card {
    background: rgba(0, 212, 255, 0.08);
    border: 1px solid rgba(0, 212, 255, 0.2);
    border-radius: 8px;
    padding: 15px;
    margin-bottom: 12px;
  }
  .server-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
  .server-name { font-weight: bold; color: #00d4ff; font-size: 1.05em; }
  .server-meta { font-family: monospace; font-size: 0.75em; color: #666; margin-top: 4px; }
  .server-version { font-size: 0.75em; color: #888; background: rgba(255,255,255,0.05); padding: 2px 8px; border-radius: 10px; margin-left: 8px; }
  .server-ip { color: #b388ff; }
  .player-count { background: #00d4ff; color: #1a1a2e; padding: 4px 12px; border-radius: 20px; font-weight: bold; font-size: 0.85em; }
  .players-list { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 10px; padding-top: 10px; border-top: 1px solid rgba(255,255,255,0.1); }

  /* Player tag */
  .player-tag {
    background: rgba(255,255,255,0.08);
    padding: 8px 12px;
    border-radius: 12px;
    display: flex;
    align-items: center;
    gap: 10px;
  }
  .player-avatar { width: 36px; height: 36px; border-radius: 50%; background: rgba(0,0,0,0.3); border: none; }
  .player-info { display: flex; flex-direction: column; gap: 2px; }
  .player-name { color: #fff; font-weight: 500; font-size: 0.9em; }
  .player-uuid { color: #666; font-size: 0.7em; font-family: monospace; }
  .player-state { display: flex; gap: 8px; font-size: 0.7em; color: #888; flex-wrap: wrap; align-items: center; }
  .state-item { display: flex; align-items: center; gap: 3px; }
  .state-item.good { color: #5f5; }
  .state-item.warn { color: #fc5; }
  .state-item.bad { color: #f55; }
  .status-badge { padding: 2px 8px; border-radius: 10px; font-size: 0.8em; font-weight: 500; white-space: nowrap; }
  .status-badge.in-game { background: rgba(0,255,136,0.2); color: #0f8; }
  .status-badge.main-menu { background: rgba(255,170,0,0.2); color: #fa0; }
  .status-badge.loading { background: rgba(0,212,255,0.2); color: #0df; }
  .status-badge.disconnected { background: rgba(136,136,136,0.2); color: #888; }
  .status-badge.server { background: rgba(179,136,255,0.2); color: #b388ff; }

  /* Pagination */
  .pagination { display: flex; justify-content: center; align-items: center; gap: 15px; margin-top: 20px; padding: 15px; }
  .pagination button {
    background: rgba(0, 212, 255, 0.2);
    border: 1px solid rgba(0, 212, 255, 0.3);
    color: #00d4ff;
    padding: 8px 16px;
    border-radius: 5px;
    cursor: pointer;
  }
  .pagination button:hover:not(:disabled) { background: rgba(0, 212, 255, 0.3); }
  .pagination button:disabled { opacity: 0.4; cursor: not-allowed; }
  .pagination span { color: #888; font-size: 0.9em; }

  /* Controls */
  .controls { display: flex; gap: 10px; align-items: center; margin-bottom: 15px; flex-wrap: wrap; }
  .controls input, .controls select {
    padding: 8px 12px;
    border-radius: 5px;
    border: 1px solid #333;
    background: rgba(0,0,0,0.3);
    color: #fff;
    font-size: 0.9em;
  }
  .controls input[type="text"] { flex: 1; min-width: 200px; }
  .btn {
    padding: 8px 16px;
    background: linear-gradient(135deg, #00d4ff, #0099cc);
    border: none;
    border-radius: 5px;
    color: #fff;
    font-weight: bold;
    cursor: pointer;
  }
  .btn:disabled { opacity: 0.5; cursor: not-allowed; }
  .btn-secondary { background: rgba(255,255,255,0.1); }
  .btn-danger { background: rgba(255,100,100,0.3); }

  /* Logs */
  .logs-container {
    max-height: 600px;
    overflow-y: auto;
    font-family: 'Monaco', 'Menlo', monospace;
    font-size: 0.8em;
    background: #0d0d1a;
    border-radius: 8px;
    padding: 10px;
  }
  .log-entry {
    padding: 8px 10px;
    border-bottom: 1px solid rgba(255,255,255,0.05);
    display: grid;
    grid-template-columns: 150px 60px 45px auto 80px;
    gap: 10px;
    cursor: pointer;
  }
  .log-entry:hover { background: rgba(0, 212, 255, 0.1); }
  .log-entry.expanded { display: block; background: rgba(0, 212, 255, 0.05); }
  .log-timestamp { color: #666; font-size: 0.85em; }
  .log-method { font-weight: bold; padding: 2px 6px; border-radius: 3px; text-align: center; font-size: 0.8em; }
  .log-method.GET { background: rgba(0, 200, 100, 0.3); color: #5f5; }
  .log-method.POST { background: rgba(0, 150, 255, 0.3); color: #5af; }
  .log-method.DELETE { background: rgba(255, 100, 100, 0.3); color: #f88; }
  .log-status { text-align: center; }
  .log-status.s2xx { color: #5f5; }
  .log-status.s4xx { color: #fc5; }
  .log-status.s5xx { color: #f55; }
  .log-url { color: #e0e0e0; word-break: break-all; }
  .log-time { color: #888; text-align: right; }
  .log-details { margin-top: 10px; padding: 10px; background: rgba(0,0,0,0.3); border-radius: 5px; white-space: pre-wrap; word-break: break-all; color: #aaa; }

  /* Charts */
  .chart-container { background: rgba(0,0,0,0.2); border-radius: 8px; padding: 15px; margin-bottom: 15px; min-height: 0; min-width: 0; }
  .chart-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px; }
  .chart-title { color: #00d4ff; font-size: 1em; }
  .chart-controls { display: flex; gap: 5px; }
  .chart-controls button {
    padding: 4px 10px;
    background: rgba(255,255,255,0.1);
    border: 1px solid rgba(255,255,255,0.2);
    color: #888;
    border-radius: 4px;
    font-size: 0.8em;
    cursor: pointer;
  }
  .chart-controls button.active { background: rgba(0, 212, 255, 0.3); color: #00d4ff; border-color: rgba(0, 212, 255, 0.5); }
  .chart-wrapper { position: relative; height: 200px; width: 100%; overflow: hidden; }
  .chart-wrapper canvas { max-height: 200px !important; }

  .no-data { color: #666; font-style: italic; padding: 40px; text-align: center; }
  .hidden { display: none !important; }

  /* Player tooltips */
  .player-tag { position: relative; }
  .player-tag[data-tooltip]:hover::after {
    content: attr(data-tooltip);
    position: absolute;
    bottom: 100%;
    left: 50%;
    transform: translateX(-50%);
    background: rgba(0, 0, 0, 0.95);
    border: 1px solid rgba(0, 212, 255, 0.3);
    color: #e0e0e0;
    padding: 8px 12px;
    border-radius: 6px;
    font-size: 0.75em;
    white-space: pre-line;
    z-index: 100;
    min-width: 150px;
    max-width: 250px;
    text-align: left;
    line-height: 1.4;
    margin-bottom: 5px;
    box-shadow: 0 4px 12px rgba(0,0,0,0.5);
  }
  .state-fps { color: #5f5; }
  .state-latency { color: #fc5; }
  .state-hw { color: #888; }

  /* Login */
  .login-overlay {
    position: fixed; top: 0; left: 0; right: 0; bottom: 0;
    background: rgba(0, 0, 0, 0.95);
    display: flex; justify-content: center; align-items: center;
    z-index: 1000;
  }
  .login-box {
    background: linear-gradient(135deg, #1a1a2e 0%, #16213e 100%);
    border: 1px solid rgba(0, 212, 255, 0.3);
    border-radius: 12px;
    padding: 40px;
    text-align: center;
    max-width: 400px;
    width: 90%;
  }
  .login-box h2 { color: #00d4ff; margin-bottom: 20px; }
  .login-box input {
    width: 100%;
    padding: 12px;
    border-radius: 5px;
    border: 1px solid #333;
    background: #0d0d1a;
    color: #fff;
    margin-bottom: 15px;
  }
  .login-box button { width: 100%; padding: 12px; }
  .login-error { color: #ff6b6b; margin-top: 10px; }
`;

const sharedScripts = `
  let adminToken = localStorage.getItem('adminToken');
  let savedPassword = localStorage.getItem('adminPassword');

  async function authFetch(url, options = {}) {
    if (!adminToken) throw new Error('Not authenticated');
    options.headers = { ...options.headers, 'X-Admin-Token': adminToken };
    const res = await fetch(url, options);
    if (res.status === 401) {
      if (savedPassword && await tryAutoLogin()) {
        options.headers['X-Admin-Token'] = adminToken;
        return fetch(url, options);
      }
      logout();
      throw new Error('Session expired');
    }
    return res;
  }

  async function tryAutoLogin() {
    if (!savedPassword) return false;
    try {
      const res = await fetch('/admin/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ password: savedPassword })
      });
      const data = await res.json();
      if (res.ok && data.token) {
        adminToken = data.token;
        localStorage.setItem('adminToken', adminToken);
        return true;
      }
    } catch (e) {}
    return false;
  }

  async function checkAuth() {
    if (!adminToken && savedPassword) return await tryAutoLogin();
    if (!adminToken) return false;
    try {
      const res = await fetch('/admin/verify', { headers: { 'X-Admin-Token': adminToken } });
      const data = await res.json();
      if (data.valid) return true;
      if (savedPassword) return await tryAutoLogin();
      return false;
    } catch (e) { return false; }
  }

  function logout() {
    adminToken = null;
    savedPassword = null;
    localStorage.removeItem('adminToken');
    localStorage.removeItem('adminPassword');
    window.location.href = '/admin';
  }

  async function loadStats() {
    try {
      const res = await authFetch('/admin/stats');
      const s = await res.json();
      document.querySelectorAll('[data-stat]').forEach(el => {
        const stat = el.dataset.stat;
        if (stat === 'players') el.textContent = s.activePlayers || 0;
        else if (stat === 'servers') el.textContent = s.activeServers || 0;
        else if (stat === 'sessions') el.textContent = s.activeSessions || 0;
        else if (stat === 'users') el.textContent = s.keys?.users || 0;
      });
      const dot = document.getElementById('redisDot');
      if (dot) dot.className = 'status-dot ' + (s.redis?.connected ? 'online' : 'offline');
    } catch (e) {}
  }
`;

const navHtml = (activePage) => `
  <nav class="top-nav">
    <a href="/admin" class="logo">Hytale Auth</a>
    <div class="nav-links">
      <a href="/admin/page/servers" class="nav-link ${activePage === 'servers' ? 'active' : ''}">Servers</a>
      <a href="/admin/page/players" class="nav-link ${activePage === 'players' ? 'active' : ''}">Players</a>
      <a href="/admin/page/logs" class="nav-link ${activePage === 'logs' ? 'active' : ''}">Logs</a>
      <a href="/admin/page/metrics" class="nav-link ${activePage === 'metrics' ? 'active' : ''}">Metrics</a>
      <a href="/admin/page/settings" class="nav-link ${activePage === 'settings' ? 'active' : ''}">Settings</a>
    </div>
    <div class="nav-right">
      <span class="status"><span class="status-dot" id="redisDot"></span>Redis</span>
      <span class="status">Players: <strong data-stat="players">-</strong></span>
      <span class="status">Servers: <strong data-stat="servers">-</strong></span>
      <button class="logout-btn" onclick="logout()">Logout</button>
    </div>
  </nav>
`;

/**
 * Servers page
 */
function handleServersPage(req, res) {
    const html = `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Servers - Hytale Admin</title>
  <style>${sharedStyles}</style>
</head>
<body>
  <div class="login-overlay" id="loginOverlay">
    <div class="login-box">
      <h2>Admin Login</h2>
      <form id="loginForm">
        <input type="password" id="loginPassword" placeholder="Password" required>
        <button type="submit" class="btn">Login</button>
      </form>
      <div class="login-error" id="loginError"></div>
    </div>
  </div>

  <div id="mainContent" class="hidden">
    ${navHtml('servers')}
    <div class="container">
      <div class="card">
        <div class="card-header">
          <span class="card-title">Active Servers</span>
          <div>
            <button class="btn btn-secondary" onclick="loadServers(1)">Refresh</button>
          </div>
        </div>
        <div id="serversList">Loading...</div>
      </div>
    </div>
  </div>

  <script>
    ${sharedScripts}

    let currentPage = 1;

    async function loadServers(page) {
      const list = document.getElementById('serversList');
      list.innerHTML = '<div class="no-data">Loading...</div>';
      try {
        const res = await authFetch('/admin/api/servers?page=' + page + '&limit=20');
        const d = await res.json();
        if (!d.servers?.length) {
          list.innerHTML = '<div class="no-data">No active servers</div>';
          return;
        }
        let html = d.servers.map(srv => \`
          <div class="server-card">
            <div class="server-header">
              <span class="server-name">
                \${srv.name || srv.audience}
                \${srv.version ? '<span class="server-version">v' + srv.version + '</span>' : ''}
              </span>
              <span class="player-count">\${srv.playerCount} players</span>
            </div>
            <div class="server-meta">
              ID: \${srv.audience}
              \${srv.ip ? ' | <span class="server-ip">' + srv.ip + '</span>' : ''}
            </div>
            \${srv.players?.length ? '<div class="players-list">' + srv.players.map(p => {
              const tt = [];
              if (p.state?.fps) tt.push('FPS: ' + Math.round(p.state.fps));
              if (p.state?.latency) tt.push('Latency: ' + Math.round(p.state.latency) + 'ms');
              if (p.state?.activity_state) tt.push('State: ' + p.state.activity_state);
              if (p.hardware?.os) tt.push('OS: ' + p.hardware.os);
              if (p.hardware?.gpu) tt.push('GPU: ' + p.hardware.gpu);
              if (p.hardware?.resolution) tt.push('Resolution: ' + p.hardware.resolution);
              if (p.hardware?.memory_mb) tt.push('RAM: ' + Math.round(p.hardware.memory_mb/1024) + 'GB');
              const tooltip = tt.length ? tt.join('\\n') : '';
              return \`
              <div class="player-tag" \${tooltip ? 'data-tooltip="' + tooltip + '"' : ''}>
                <iframe class="player-avatar" src="/avatar/\${p.uuid}/head?bg=black" loading="lazy"></iframe>
                <div class="player-info">
                  <span class="player-name">\${p.username}</span>
                  <span class="player-uuid">\${p.uuid.substring(0,8)}...</span>
                  \${p.state?.fps ? '<div class="player-state"><span class="state-fps">' + Math.round(p.state.fps) + ' FPS</span>' + (p.state?.latency ? ' <span class="state-latency">' + Math.round(p.state.latency) + 'ms</span>' : '') + '</div>' : ''}
                </div>
              </div>\`;
            }).join('') + '</div>' : ''}
            \${srv.hasMore ? '<div style="color:#888;font-size:0.8em;margin-top:10px;">+ more players...</div>' : ''}
          </div>
        \`).join('');

        html += '<div class="pagination">' +
          '<button onclick="loadServers(' + (d.page - 1) + ')" ' + (d.page <= 1 ? 'disabled' : '') + '>Prev</button>' +
          '<span>Page ' + d.page + ' / ' + d.totalPages + ' (' + d.total + ' servers)</span>' +
          '<button onclick="loadServers(' + (d.page + 1) + ')" ' + (d.page >= d.totalPages ? 'disabled' : '') + '>Next</button>' +
          '</div>';

        list.innerHTML = html;
        currentPage = page;
      } catch (e) {
        list.innerHTML = '<div class="no-data">Error: ' + e.message + '</div>';
      }
    }

    document.getElementById('loginForm').addEventListener('submit', async (e) => {
      e.preventDefault();
      const password = document.getElementById('loginPassword').value;
      try {
        const res = await fetch('/admin/login', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ password })
        });
        const data = await res.json();
        if (res.ok && data.token) {
          adminToken = data.token;
          savedPassword = password;
          localStorage.setItem('adminToken', adminToken);
          localStorage.setItem('adminPassword', password);
          init();
        } else {
          document.getElementById('loginError').textContent = data.error || 'Failed';
        }
      } catch (e) {
        document.getElementById('loginError').textContent = 'Connection error';
      }
    });

    async function init() {
      document.getElementById('loginOverlay').classList.add('hidden');
      document.getElementById('mainContent').classList.remove('hidden');
      loadStats();
      loadServers(1);
      setInterval(loadStats, 30000);
    }

    (async () => {
      if (await checkAuth()) init();
    })();
  </script>
</body>
</html>`;
    sendHtml(res, 200, html);
}

/**
 * Players page
 */
function handlePlayersPage(req, res) {
    const html = `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Players - Hytale Admin</title>
  <style>${sharedStyles}</style>
</head>
<body>
  <div class="login-overlay" id="loginOverlay">
    <div class="login-box">
      <h2>Admin Login</h2>
      <form id="loginForm">
        <input type="password" id="loginPassword" placeholder="Password" required>
        <button type="submit" class="btn">Login</button>
      </form>
      <div class="login-error" id="loginError"></div>
    </div>
  </div>

  <div id="mainContent" class="hidden">
    ${navHtml('players')}
    <div class="container">
      <div class="card">
        <div class="card-header">
          <span class="card-title">Active Players</span>
          <button class="btn btn-secondary" onclick="loadPlayers(1)">Refresh</button>
        </div>
        <div class="controls">
          <input type="text" id="searchInput" placeholder="Search by username or UUID..." onkeyup="if(event.key==='Enter')searchPlayers()">
          <button class="btn" onclick="searchPlayers()">Search</button>
          <button class="btn btn-secondary" onclick="clearSearch()">Clear</button>
        </div>
        <div id="playersList">Loading...</div>
      </div>
    </div>
  </div>

  <script>
    ${sharedScripts}

    let currentPage = 1;
    let searchMode = false;

    async function loadPlayers(page) {
      const list = document.getElementById('playersList');
      list.innerHTML = '<div class="no-data">Loading...</div>';
      searchMode = false;
      try {
        const res = await authFetch('/admin/api/players?page=' + page + '&limit=50');
        const d = await res.json();
        if (!d.players?.length) {
          list.innerHTML = '<div class="no-data">No active players</div>';
          return;
        }
        renderPlayers(d.players, d.page, d.totalPages, d.total);
        currentPage = page;
      } catch (e) {
        list.innerHTML = '<div class="no-data">Error: ' + e.message + '</div>';
      }
    }

    async function searchPlayers() {
      const q = document.getElementById('searchInput').value.trim();
      if (q.length < 2) { loadPlayers(1); return; }

      const list = document.getElementById('playersList');
      list.innerHTML = '<div class="no-data">Searching...</div>';
      searchMode = true;

      try {
        const res = await authFetch('/admin/search?q=' + encodeURIComponent(q) + '&limit=100');
        const d = await res.json();
        if (!d.results?.length) {
          list.innerHTML = '<div class="no-data">No players found</div>';
          return;
        }
        renderPlayers(d.results.map(p => ({
          uuid: p.uuid,
          username: p.username,
          server: p.servers?.[0]?.audience,
          state: p.state
        })), 1, 1, d.results.length);
      } catch (e) {
        list.innerHTML = '<div class="no-data">Error: ' + e.message + '</div>';
      }
    }

    function clearSearch() {
      document.getElementById('searchInput').value = '';
      loadPlayers(1);
    }

    function getStatusBadge(state, server, connected) {
      // Determine player status from telemetry state
      const currentState = state?.current_state || '';
      const isConnected = connected === true || state?.connected === true;

      if (currentState.includes('InGame') || currentState.includes('Playing')) {
        return '<span class="status-badge in-game">In Game</span>';
      } else if (currentState === 'MainMenu' || currentState.includes('Menu')) {
        return '<span class="status-badge main-menu">Main Menu</span>';
      } else if (currentState.includes('Loading') || currentState.includes('Connecting')) {
        return '<span class="status-badge loading">Loading</span>';
      } else if (!isConnected && !server) {
        return '<span class="status-badge disconnected">Offline</span>';
      }
      return '';
    }

    function renderPlayers(players, page, totalPages, total) {
      const list = document.getElementById('playersList');
      let html = '<div class="players-list" style="gap:15px">';
      html += players.map(p => {
        const st = p.state || {};
        const hw = p.hardware || {};
        const fc = st.fps > 50 ? 'good' : st.fps > 30 ? 'warn' : st.fps ? 'bad' : '';
        const lc = st.latency < 50 ? 'good' : st.latency < 100 ? 'warn' : st.latency ? 'bad' : '';
        // Build tooltip
        const tt = [];
        if (st.current_state) tt.push('State: ' + st.current_state);
        if (st.fps) tt.push('FPS: ' + Math.round(st.fps));
        if (st.latency) tt.push('Latency: ' + Math.round(st.latency) + 'ms');
        if (st.activity_state) tt.push('Activity: ' + st.activity_state);
        if (hw.os) tt.push('OS: ' + hw.os);
        if (hw.gpu) tt.push('GPU: ' + hw.gpu);
        if (hw.resolution) tt.push('Resolution: ' + hw.resolution);
        if (hw.memory_mb) tt.push('RAM: ' + Math.round(hw.memory_mb/1024) + 'GB');
        if (hw.cpu_cores) tt.push('CPU: ' + hw.cpu_cores + ' cores');
        const tooltip = tt.length ? tt.join('\\n') : '';
        const statusBadge = getStatusBadge(st, p.server, st.connected);
        const serverBadge = p.server ? '<span class="status-badge server">' + p.server.substring(0,8) + '</span>' : '';
        return \`
        <div class="player-tag" style="padding:12px" \${tooltip ? 'data-tooltip="' + tooltip + '"' : ''}>
          <iframe class="player-avatar" style="width:50px;height:50px" src="/avatar/\${p.uuid}/head?bg=black" loading="lazy"></iframe>
          <div class="player-info">
            <span class="player-name" style="font-size:1em">\${p.username}</span>
            <span class="player-uuid">\${p.uuid}</span>
            <div class="player-state">
              \${statusBadge}
              \${serverBadge}
              \${st.fps ? '<span class="state-item ' + fc + '">' + Math.round(st.fps) + ' FPS</span>' : ''}
              \${st.latency ? '<span class="state-item ' + lc + '">' + Math.round(st.latency) + 'ms</span>' : ''}
            </div>
          </div>
        </div>\`;
      }).join('');
      html += '</div>';

      if (!searchMode) {
        html += '<div class="pagination">' +
          '<button onclick="loadPlayers(' + (page - 1) + ')" ' + (page <= 1 ? 'disabled' : '') + '>Prev</button>' +
          '<span>Page ' + page + ' / ' + totalPages + ' (' + total + ' players)</span>' +
          '<button onclick="loadPlayers(' + (page + 1) + ')" ' + (page >= totalPages ? 'disabled' : '') + '>Next</button>' +
          '</div>';
      }

      list.innerHTML = html;
    }

    document.getElementById('loginForm').addEventListener('submit', async (e) => {
      e.preventDefault();
      const password = document.getElementById('loginPassword').value;
      try {
        const res = await fetch('/admin/login', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ password })
        });
        const data = await res.json();
        if (res.ok && data.token) {
          adminToken = data.token;
          savedPassword = password;
          localStorage.setItem('adminToken', adminToken);
          localStorage.setItem('adminPassword', password);
          init();
        } else {
          document.getElementById('loginError').textContent = data.error || 'Failed';
        }
      } catch (e) {
        document.getElementById('loginError').textContent = 'Connection error';
      }
    });

    async function init() {
      document.getElementById('loginOverlay').classList.add('hidden');
      document.getElementById('mainContent').classList.remove('hidden');
      loadStats();
      loadPlayers(1);
      setInterval(loadStats, 30000);
    }

    (async () => {
      if (await checkAuth()) init();
    })();
  </script>
</body>
</html>`;
    sendHtml(res, 200, html);
}

/**
 * Logs page
 */
function handleLogsPage(req, res) {
    const html = `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Logs - Hytale Admin</title>
  <style>${sharedStyles}</style>
</head>
<body>
  <div class="login-overlay" id="loginOverlay">
    <div class="login-box">
      <h2>Admin Login</h2>
      <form id="loginForm">
        <input type="password" id="loginPassword" placeholder="Password" required>
        <button type="submit" class="btn">Login</button>
      </form>
      <div class="login-error" id="loginError"></div>
    </div>
  </div>

  <div id="mainContent" class="hidden">
    ${navHtml('logs')}
    <div class="container">
      <div class="card">
        <div class="card-header">
          <span class="card-title">Request Logs</span>
          <span id="logStats" style="color:#888;font-size:0.85em"></span>
        </div>
        <div class="controls">
          <input type="text" id="logFilter" placeholder="Filter (URL, IP, method...)">
          <select id="logLines">
            <option value="50">50 lines</option>
            <option value="100" selected>100 lines</option>
            <option value="200">200 lines</option>
            <option value="500">500 lines</option>
          </select>
          <select id="logMethod">
            <option value="">All methods</option>
            <option value="GET">GET</option>
            <option value="POST">POST</option>
            <option value="DELETE">DELETE</option>
          </select>
          <button class="btn" onclick="loadLogs()">Load</button>
          <label style="color:#888;font-size:0.85em;display:flex;align-items:center;gap:5px">
            <input type="checkbox" id="autoRefresh" onchange="toggleAuto()"> Auto
          </label>
        </div>
        <div class="logs-container" id="logsContainer">
          <div class="no-data">Click Load to view logs</div>
        </div>
      </div>
    </div>
  </div>

  <script>
    ${sharedScripts}

    let autoInterval = null;
    let logsData = [];

    async function loadLogStats() {
      try {
        const res = await authFetch('/admin/logs/stats');
        const s = await res.json();
        document.getElementById('logStats').textContent = s.exists
          ? s.sizeHuman + ' | ' + s.lines.toLocaleString() + ' lines'
          : 'No log file';
      } catch (e) {}
    }

    async function loadLogs() {
      const container = document.getElementById('logsContainer');
      container.innerHTML = '<div class="no-data">Loading...</div>';

      const filter = document.getElementById('logFilter').value;
      const lines = document.getElementById('logLines').value;
      const method = document.getElementById('logMethod').value;

      try {
        let url = '/admin/logs?lines=' + lines;
        if (filter) url += '&filter=' + encodeURIComponent(filter);

        const res = await authFetch(url);
        const d = await res.json();
        let logs = d.logs || [];

        if (method) logs = logs.filter(l => l.method === method);

        if (!logs.length) {
          container.innerHTML = '<div class="no-data">No logs found</div>';
          return;
        }

        logsData = logs;
        container.innerHTML = logs.map((l, i) => {
          const sc = l.statusCode ? 's' + Math.floor(l.statusCode / 100) + 'xx' : '';
          const ts = l.timestamp ? new Date(l.timestamp).toLocaleString() : '-';
          const su = l.url?.length > 50 ? l.url.substring(0, 50) + '...' : l.url;
          return '<div class="log-entry" onclick="toggleLog(' + i + ')" data-i="' + i + '">' +
            '<span class="log-timestamp">' + ts + '</span>' +
            '<span class="log-method ' + (l.method || '') + '">' + (l.method || '-') + '</span>' +
            '<span class="log-status ' + sc + '">' + (l.statusCode || '-') + '</span>' +
            '<span class="log-url">' + (su || '-') + '</span>' +
            '<span class="log-time">' + (l.responseTime || '-') + '</span>' +
            '</div>';
        }).join('');

        loadLogStats();
      } catch (e) {
        container.innerHTML = '<div class="no-data">Error: ' + e.message + '</div>';
      }
    }

    function toggleLog(i) {
      const l = logsData[i];
      if (!l) return;
      const el = document.querySelector('[data-i="' + i + '"]');
      if (el.classList.contains('expanded')) {
        el.classList.remove('expanded');
        el.querySelector('.log-details')?.remove();
        return;
      }
      document.querySelectorAll('.log-entry.expanded').forEach(x => {
        x.classList.remove('expanded');
        x.querySelector('.log-details')?.remove();
      });
      el.classList.add('expanded');
      const d = document.createElement('div');
      d.className = 'log-details';
      d.textContent = 'URL: ' + l.url + '\\nIP: ' + l.ip + '\\nUA: ' + l.userAgent + '\\nHost: ' + l.host +
        (l.body ? '\\nBody: ' + JSON.stringify(l.body, null, 2) : '');
      el.appendChild(d);
    }

    function toggleAuto() {
      if (document.getElementById('autoRefresh').checked) {
        loadLogs();
        autoInterval = setInterval(loadLogs, 5000);
      } else if (autoInterval) {
        clearInterval(autoInterval);
        autoInterval = null;
      }
    }

    document.getElementById('loginForm').addEventListener('submit', async (e) => {
      e.preventDefault();
      const password = document.getElementById('loginPassword').value;
      try {
        const res = await fetch('/admin/login', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ password })
        });
        const data = await res.json();
        if (res.ok && data.token) {
          adminToken = data.token;
          savedPassword = password;
          localStorage.setItem('adminToken', adminToken);
          localStorage.setItem('adminPassword', password);
          init();
        } else {
          document.getElementById('loginError').textContent = data.error || 'Failed';
        }
      } catch (e) {
        document.getElementById('loginError').textContent = 'Connection error';
      }
    });

    async function init() {
      document.getElementById('loginOverlay').classList.add('hidden');
      document.getElementById('mainContent').classList.remove('hidden');
      loadStats();
      loadLogStats();
      setInterval(loadStats, 30000);
    }

    (async () => {
      if (await checkAuth()) init();
    })();
  </script>
</body>
</html>`;
    sendHtml(res, 200, html);
}

/**
 * Metrics page with charts
 */
function handleMetricsPage(req, res) {
    const html = `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Metrics - Hytale Admin</title>
  <script src="https://cdn.jsdelivr.net/npm/chart.js"></script>
  <style>
    ${sharedStyles}
    .section-title { color: #00d4ff; font-size: 1.1em; margin: 25px 0 15px; padding-bottom: 8px; border-bottom: 1px solid rgba(0,212,255,0.2); }
    .pie-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(250px, 1fr)); gap: 20px; }
    .pie-container { background: rgba(0,0,0,0.2); border-radius: 8px; padding: 15px; }
    .pie-title { color: #888; font-size: 0.9em; margin-bottom: 10px; text-align: center; }
    .pie-wrapper { position: relative; height: 180px; }
    /* Horizontal bar chart for hardware stats */
    .hw-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 15px; }
    .hw-container { background: rgba(0,0,0,0.2); border-radius: 8px; padding: 15px; }
    .hw-title { color: #888; font-size: 0.85em; margin-bottom: 8px; }
    .hw-bar-row { display: flex; align-items: center; margin: 4px 0; font-size: 0.8em; }
    .hw-bar-label { width: 90px; color: #aaa; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; flex-shrink: 0; }
    .hw-bar-track { flex: 1; height: 16px; background: rgba(255,255,255,0.05); border-radius: 3px; margin: 0 8px; overflow: hidden; min-width: 60px; }
    .hw-bar-fill { height: 100%; background: linear-gradient(90deg, #00d4ff, #0099cc); border-radius: 3px; transition: width 0.3s; }
    .hw-bar-value { width: 75px; color: #666; text-align: right; flex-shrink: 0; font-family: monospace; }
    .hw-container.wide { grid-column: span 2; }
    .hw-container.wide .hw-bar-label { width: 180px; }
  </style>
</head>
<body>
  <div class="login-overlay" id="loginOverlay">
    <div class="login-box">
      <h2>Admin Login</h2>
      <form id="loginForm">
        <input type="password" id="loginPassword" placeholder="Password" required>
        <button type="submit" class="btn">Login</button>
      </form>
      <div class="login-error" id="loginError"></div>
    </div>
  </div>

  <div id="mainContent" class="hidden">
    ${navHtml('metrics')}
    <div class="container">
      <!-- Time range controls -->
      <div class="controls" style="margin-bottom:20px">
        <span style="color:#888">Time Range:</span>
        <button class="btn btn-secondary range-btn" data-range="5m">5m</button>
        <button class="btn btn-secondary range-btn" data-range="15m">15m</button>
        <button class="btn btn-secondary range-btn active" data-range="1h">1h</button>
        <button class="btn btn-secondary range-btn" data-range="6h">6h</button>
        <button class="btn btn-secondary range-btn" data-range="24h">24h</button>
        <button class="btn btn-secondary range-btn" data-range="7d">7d</button>
        <button class="btn" onclick="refreshCharts()" style="margin-left:auto">Refresh</button>
        <label style="color:#888;font-size:0.85em;display:flex;align-items:center;gap:5px">
          <input type="checkbox" id="autoRefresh" checked onchange="toggleAuto()"> Auto (30s)
        </label>
      </div>

      <!-- Main time-series charts -->
      <div style="display:grid;grid-template-columns:repeat(auto-fit,minmax(400px,1fr));gap:20px">
        <div class="chart-container" style="grid-column: span 2">
          <div class="chart-header"><span class="chart-title">Activity Overview (Players / Servers / Sessions)</span></div>
          <div class="chart-wrapper" style="height:250px"><canvas id="activityChart"></canvas></div>
        </div>
        <div class="chart-container">
          <div class="chart-header"><span class="chart-title">Requests/min</span></div>
          <div class="chart-wrapper"><canvas id="requestsChart"></canvas></div>
        </div>
      </div>

      <!-- Performance Distribution -->
      <h3 class="section-title">Performance Distribution (current heartbeats)</h3>
      <div style="display:grid;grid-template-columns:repeat(auto-fit,minmax(400px,1fr));gap:20px">
        <div class="chart-container">
          <div class="chart-header"><span class="chart-title">FPS & Frame Time Distribution</span></div>
          <div class="chart-wrapper"><canvas id="fpsChart"></canvas></div>
        </div>
        <div class="chart-container">
          <div class="chart-header"><span class="chart-title">Avg FPS / Frame Time p99 Over Time</span></div>
          <div class="chart-wrapper"><canvas id="fpsLineChart"></canvas></div>
        </div>
        <div class="chart-container">
          <div class="chart-header"><span class="chart-title">Latency Distribution</span></div>
          <div class="chart-wrapper"><canvas id="latencyChart"></canvas></div>
        </div>
        <div class="chart-container">
          <div class="chart-header"><span class="chart-title">Avg Latency Over Time</span></div>
          <div class="chart-wrapper"><canvas id="latencyLineChart"></canvas></div>
        </div>
        <div class="chart-container">
          <div class="chart-header"><span class="chart-title">Memory Usage Distribution</span></div>
          <div class="chart-wrapper"><canvas id="memoryChart"></canvas></div>
        </div>
        <div class="chart-container">
          <div class="chart-header"><span class="chart-title">Avg Memory Over Time</span></div>
          <div class="chart-wrapper"><canvas id="memoryLineChart"></canvas></div>
        </div>
        <div class="chart-container">
          <div class="chart-header"><span class="chart-title">Session Duration Distribution</span></div>
          <div class="chart-wrapper"><canvas id="sessionDurationChart"></canvas></div>
        </div>
        <div class="chart-container">
          <div class="chart-header"><span class="chart-title">Session Duration Over Time (Avg / p50 / p90 / p99)</span></div>
          <div class="chart-wrapper"><canvas id="sessionDurationLineChart"></canvas></div>
        </div>
      </div>

      <!-- Session Analytics -->
      <h3 class="section-title">Session Analytics</h3>
      <div class="pie-grid">
        <div class="pie-container">
          <div class="pie-title">Language</div>
          <div class="pie-wrapper"><canvas id="languageChart"></canvas></div>
        </div>
        <div class="pie-container">
          <div class="pie-title">Disconnect Reasons</div>
          <div class="pie-wrapper"><canvas id="disconnectChart"></canvas></div>
        </div>
        <div class="pie-container">
          <div class="pie-title">Game Modes</div>
          <div class="pie-wrapper"><canvas id="gameModeChart"></canvas></div>
        </div>
      </div>

      <!-- Event Metrics -->
      <h3 class="section-title">Event Metrics</h3>
      <div style="display:grid;grid-template-columns:repeat(auto-fit,minmax(400px,1fr));gap:20px">
        <div class="chart-container">
          <div class="chart-header"><span class="chart-title">Total Playtime Over Time (hours)</span></div>
          <div class="chart-wrapper"><canvas id="playtimeLineChart"></canvas></div>
        </div>
        <div class="chart-container">
          <div class="chart-header"><span class="chart-title">Connect Time Distribution (ms)</span></div>
          <div class="chart-wrapper"><canvas id="connectTimeChart"></canvas></div>
        </div>
        <div class="chart-container">
          <div class="chart-header"><span class="chart-title">World Load Time Distribution (ms)</span></div>
          <div class="chart-wrapper"><canvas id="worldLoadChart"></canvas></div>
        </div>
        <div class="chart-container">
          <div class="chart-header"><span class="chart-title">Avg World Load Time Over Time</span></div>
          <div class="chart-wrapper"><canvas id="worldLoadLineChart"></canvas></div>
        </div>
      </div>

      <!-- Totals -->
      <div class="stats-bar" style="margin-top:20px">
        <div class="stat-item"><div class="stat-value" id="totalPlaytime">0h</div><div class="stat-label">Total Playtime</div></div>
        <div class="stat-item"><div class="stat-value" id="totalSessions">0</div><div class="stat-label">Sessions Ended</div></div>
        <div class="stat-item"><div class="stat-value" id="avgSessionFps">0</div><div class="stat-label">Avg Session FPS</div></div>
      </div>

      <!-- Hardware stats - horizontal bar charts (3 cols x 3 rows) -->
      <h3 class="section-title">Hardware Statistics
        (<span id="hwTotal">0</span> active / <span id="hwTotalAll">0</span> total players)
        <label style="font-size:12px;margin-left:10px;font-weight:normal;">
          <input type="checkbox" id="hwShowAll" onchange="loadHardwareStats()"> Show all players
        </label>
      </h3>
      <div class="hw-grid">
        <!-- Row 1 -->
        <div class="hw-container">
          <div class="hw-title">Operating System</div>
          <div id="hwOs"></div>
        </div>
        <div class="hw-container">
          <div class="hw-title">GPU Vendor</div>
          <div id="hwGpuVendor"></div>
        </div>
        <div class="hw-container">
          <div class="hw-title">Screen Resolution</div>
          <div id="hwResolution"></div>
        </div>
        <!-- Row 2 -->
        <div class="hw-container wide">
          <div class="hw-title">GPU Model (Top 10)</div>
          <div id="hwGpuModel"></div>
        </div>
        <div class="hw-container">
          <div class="hw-title">Refresh Rate</div>
          <div id="hwRefreshRate"></div>
        </div>
        <!-- Row 3 -->
        <div class="hw-container">
          <div class="hw-title">CPU Cores</div>
          <div id="hwCpuCores"></div>
        </div>
        <div class="hw-container">
          <div class="hw-title">System Memory</div>
          <div id="hwMemory"></div>
        </div>
        <div class="hw-container">
          <div class="hw-title">Display Mode</div>
          <div id="hwDisplayMode"></div>
        </div>
      </div>

      <!-- Raw metrics -->
      <h3 class="section-title">Raw Counters (in-memory, resets on restart)</h3>
      <div class="card">
        <div id="metricsSnapshot" style="font-family:monospace;font-size:0.85em;white-space:pre-wrap;max-height:200px;overflow:auto"></div>
      </div>
    </div>
  </div>

  <script>
    ${sharedScripts}

    let charts = {};
    let currentRange = '1h';
    let autoInterval = null;

    const chartConfig = {
      responsive: true,
      maintainAspectRatio: false,
      resizeDelay: 100,
      plugins: { legend: { display: false } },
      scales: {
        x: { grid: { color: 'rgba(255,255,255,0.1)' }, ticks: { color: '#888', maxRotation: 45 } },
        y: { grid: { color: 'rgba(255,255,255,0.1)' }, ticks: { color: '#888' }, beginAtZero: true }
      }
    };

    const pieConfig = {
      responsive: true,
      maintainAspectRatio: false,
      plugins: {
        legend: { position: 'right', labels: { color: '#888', boxWidth: 12, font: { size: 10 } } }
      }
    };

    const colors = ['#00d4ff', '#b388ff', '#00ff88', '#ffaa00', '#ff6b6b', '#ff88cc', '#88ffcc', '#ffcc88'];

    function createLineChart(id, label, color) {
      const ctx = document.getElementById(id)?.getContext('2d');
      if (!ctx) return null;
      return new Chart(ctx, {
        type: 'line',
        data: { labels: [], datasets: [{ label, data: [], borderColor: color, backgroundColor: color + '33', fill: true, tension: 0.4, pointRadius: 2 }] },
        options: chartConfig
      });
    }

    function createBarChart(id, labels, color) {
      const ctx = document.getElementById(id)?.getContext('2d');
      if (!ctx) return null;
      return new Chart(ctx, {
        type: 'bar',
        data: { labels, datasets: [{ data: [], backgroundColor: color, borderColor: color, borderWidth: 1 }] },
        options: { ...chartConfig, plugins: { legend: { display: false } } }
      });
    }

    function createPieChart(id) {
      const ctx = document.getElementById(id)?.getContext('2d');
      if (!ctx) return null;
      return new Chart(ctx, {
        type: 'doughnut',
        data: { labels: [], datasets: [{ data: [], backgroundColor: colors }] },
        options: pieConfig
      });
    }

    function createMultiLineChart(id, datasets) {
      const ctx = document.getElementById(id)?.getContext('2d');
      if (!ctx) return null;
      return new Chart(ctx, {
        type: 'bar',
        data: {
          labels: [],
          datasets: datasets.map((ds, i) => ({
            label: ds.label,
            data: [],
            backgroundColor: ds.color + '88',
            borderColor: ds.color,
            borderWidth: 1
          }))
        },
        options: { ...chartConfig, plugins: { legend: { display: true, labels: { color: '#888' } } } }
      });
    }

    function createActivityChart(id) {
      const ctx = document.getElementById(id)?.getContext('2d');
      if (!ctx) return null;
      return new Chart(ctx, {
        type: 'line',
        data: {
          labels: [],
          datasets: [
            { label: 'Players', data: [], borderColor: '#00d4ff', backgroundColor: '#00d4ff33', fill: false, tension: 0.4, pointRadius: 2, borderWidth: 2 },
            { label: 'Servers', data: [], borderColor: '#b388ff', backgroundColor: '#b388ff33', fill: false, tension: 0.4, pointRadius: 2, borderWidth: 2 },
            { label: 'Sessions', data: [], borderColor: '#ffaa00', backgroundColor: '#ffaa0033', fill: false, tension: 0.4, pointRadius: 2, borderWidth: 2 }
          ]
        },
        options: {
          ...chartConfig,
          plugins: { legend: { display: true, labels: { color: '#888', usePointStyle: true } } }
        }
      });
    }

    function initCharts() {
      // Combined activity chart (players + servers + sessions)
      charts.activity = createActivityChart('activityChart');
      charts.requests = createLineChart('requestsChart', 'Requests/min', '#00ff88');

      // Performance histograms (merged: FPS + Frame Time in one chart)
      charts.fps = createMultiLineChart('fpsChart', [
        { label: 'FPS', color: '#00d4ff' },
        { label: 'Frame Time p99', color: '#ff6b6b' }
      ]);
      charts.fpsLine = createDualLineChart('fpsLineChart', [
        { label: 'Avg FPS', color: '#00d4ff' },
        { label: 'Frame Time p99 (ms)', color: '#ff6b6b', yAxisID: 'y1' }
      ]);
      charts.latency = createBarChart('latencyChart', ['<10', '10-25', '25-50', '50-100', '100-150', '150-200', '200-300', '300+'], '#ff6b6b');
      charts.latencyLine = createLineChart('latencyLineChart', 'Avg Latency (ms)', '#ff6b6b');
      charts.memory = createBarChart('memoryChart', ['<512', '512-1G', '1-2G', '2-4G', '4-8G', '8-16G', '16G+'], '#b388ff');
      charts.memoryLine = createLineChart('memoryLineChart', 'Avg Memory (MB)', '#b388ff');
      charts.sessionDuration = createBarChart('sessionDurationChart', ['<1m', '1-5m', '5-10m', '10-30m', '30m-1h', '1-2h', '2-4h', '4h+'], '#00ff88');
      charts.sessionDurationLine = createMultiLineChartForPercentiles('sessionDurationLineChart', [
        { label: 'Avg', color: '#00ff88' },
        { label: 'p50', color: '#00d4ff' },
        { label: 'p90', color: '#ffaa00' },
        { label: 'p99', color: '#ff6b6b' }
      ]);

      // Event charts
      charts.playtimeLine = createLineChart('playtimeLineChart', 'Total Hours', '#b388ff');
      charts.connectTime = createBarChart('connectTimeChart', ['<50', '50-100', '100-200', '200-500', '500-1s', '1-2s', '2s+'], '#ffaa00');
      charts.worldLoad = createBarChart('worldLoadChart', ['<500', '500-1s', '1-2s', '2-5s', '5-10s', '10-20s', '20s+'], '#ff88cc');
      charts.worldLoadLine = createLineChart('worldLoadLineChart', 'Avg Load Time (ms)', '#ff88cc');

      // Session analytics pie charts
      charts.language = createPieChart('languageChart');
      charts.disconnect = createPieChart('disconnectChart');
      charts.gameMode = createPieChart('gameModeChart');
    }

    function createDualLineChart(id, datasets) {
      const ctx = document.getElementById(id)?.getContext('2d');
      if (!ctx) return null;
      return new Chart(ctx, {
        type: 'line',
        data: {
          labels: [],
          datasets: datasets.map(ds => ({
            label: ds.label,
            data: [],
            borderColor: ds.color,
            backgroundColor: ds.color + '33',
            fill: false,
            tension: 0.4,
            pointRadius: 2,
            yAxisID: ds.yAxisID || 'y'
          }))
        },
        options: {
          ...chartConfig,
          plugins: { legend: { display: true, labels: { color: '#888' } } },
          scales: {
            x: { grid: { color: 'rgba(255,255,255,0.1)' }, ticks: { color: '#888', maxRotation: 45 } },
            y: { type: 'linear', position: 'left', grid: { color: 'rgba(255,255,255,0.1)' }, ticks: { color: '#888' }, beginAtZero: true, title: { display: true, text: 'FPS', color: '#888' } },
            y1: { type: 'linear', position: 'right', grid: { drawOnChartArea: false }, ticks: { color: '#888' }, beginAtZero: true, title: { display: true, text: 'ms', color: '#888' } }
          }
        }
      });
    }

    function createMultiLineChartForPercentiles(id, datasets) {
      const ctx = document.getElementById(id)?.getContext('2d');
      if (!ctx) return null;
      return new Chart(ctx, {
        type: 'line',
        data: {
          labels: [],
          datasets: datasets.map(ds => ({
            label: ds.label,
            data: [],
            borderColor: ds.color,
            backgroundColor: ds.color + '33',
            fill: false,
            tension: 0.4,
            pointRadius: 2,
            borderWidth: ds.label === 'Avg' ? 2 : 1
          }))
        },
        options: {
          ...chartConfig,
          plugins: { legend: { display: true, labels: { color: '#888', usePointStyle: true, boxWidth: 10 } } }
        }
      });
    }

    function renderHwBars(containerId, data, limit = 8, total = null) {
      const el = document.getElementById(containerId);
      if (!el || !data) { if(el) el.innerHTML = '<div style="color:#666;font-size:0.8em">No data</div>'; return; }
      const entries = Object.entries(data).filter(([k,v]) => v > 0).sort((a,b) => b[1] - a[1]).slice(0, limit);
      if (entries.length === 0) { el.innerHTML = '<div style="color:#666;font-size:0.8em">No data</div>'; return; }
      const max = Math.max(...entries.map(([,v]) => v));
      const sum = total || Object.values(data).reduce((a,b) => a + b, 0);
      el.innerHTML = entries.map(([label, count]) => {
        const barPct = (count / max * 100).toFixed(0);
        const valuePct = (count / sum * 100).toFixed(1);
        return '<div class="hw-bar-row">' +
          '<span class="hw-bar-label" title="' + label + '">' + label + '</span>' +
          '<div class="hw-bar-track"><div class="hw-bar-fill" style="width:' + barPct + '%"></div></div>' +
          '<span class="hw-bar-value">' + count + ' (' + valuePct + '%)</span>' +
        '</div>';
      }).join('');
    }

    async function loadTimeSeries(metric, chart) {
      if (!chart) return;
      try {
        const res = await authFetch('/admin/api/metrics/timeseries?metric=' + metric + '&range=' + currentRange);
        const d = await res.json();
        // VictoriaMetrics returns points as {timestamp, value}
        const labels = d.points.map(p => {
          const dt = new Date(p.timestamp);
          return currentRange === '7d' ? dt.toLocaleDateString() : dt.toLocaleTimeString([], {hour:'2-digit',minute:'2-digit'});
        });
        const values = d.points.map(p => p.value || 0);
        chart.data.labels = labels;
        chart.data.datasets[0].data = values;
        chart.update('none');
      } catch (e) { console.error('Failed to load', metric, e); }
    }

    async function loadSnapshot() {
      try {
        const res = await authFetch('/admin/api/metrics/snapshot');
        const d = await res.json();
        document.getElementById('metricsSnapshot').textContent = JSON.stringify(d, null, 2);

        // FPS + Frame Time combined chart
        if (d.histograms?.fps && charts.fps) {
          charts.fps.data.labels = ['<15', '15-30', '30-45', '45-60', '60-90', '90-120', '120-144', '144+'];
          charts.fps.data.datasets[0].data = d.histograms.fps.counts;
          if (d.histograms.frame_time) {
            charts.fps.data.datasets[1].data = d.histograms.frame_time.counts;
          }
          charts.fps.update('none');
        }

        // Latency
        if (d.histograms?.latency && charts.latency) {
          charts.latency.data.datasets[0].data = d.histograms.latency.counts;
          charts.latency.update('none');
        }

        // Memory
        if (d.histograms?.memory && charts.memory) {
          charts.memory.data.datasets[0].data = d.histograms.memory.counts;
          charts.memory.update('none');
        }

        // Session duration
        if (d.histograms?.session_duration && charts.sessionDuration) {
          charts.sessionDuration.data.datasets[0].data = d.histograms.session_duration.counts;
          charts.sessionDuration.update('none');
        }

        // Connect time
        if (d.histograms?.connect_time && charts.connectTime) {
          charts.connectTime.data.datasets[0].data = d.histograms.connect_time.counts;
          charts.connectTime.update('none');
        }

        // World load time
        if (d.histograms?.world_load_time && charts.worldLoad) {
          charts.worldLoad.data.datasets[0].data = d.histograms.world_load_time.counts;
          charts.worldLoad.update('none');
        }

        // Session analytics pie charts from distributions
        function updatePie(chart, data, limit = 10) {
          if (!chart || !data) return;
          const entries = Object.entries(data).filter(([k,v]) => v > 0).sort((a,b) => b[1] - a[1]).slice(0, limit);
          chart.data.labels = entries.map(([k]) => k);
          chart.data.datasets[0].data = entries.map(([,v]) => v);
          chart.update('none');
        }

        if (d.distributions) {
          updatePie(charts.language, d.distributions.languages);
          updatePie(charts.disconnect, d.distributions.server_disconnects);
          updatePie(charts.gameMode, d.distributions.world_joins);
        }

        // Update totals
        if (d.counters) {
          const playtimeHours = d.counters.total_playtime_hours || 0;
          document.getElementById('totalPlaytime').textContent = playtimeHours + 'h';
        }

        // Calculate avg session FPS from histogram if available
        if (d.histograms?.session_avg_fps) {
          const counts = d.histograms.session_avg_fps.counts;
          const buckets = [15, 30, 45, 60, 90, 120, 144, 240];
          let total = 0, weighted = 0;
          for (let i = 0; i < counts.length; i++) {
            total += counts[i];
            weighted += counts[i] * (buckets[i] || 240);
          }
          const avg = total > 0 ? Math.round(weighted / total) : 0;
          document.getElementById('avgSessionFps').textContent = avg;
        }

      } catch (e) { console.error('Snapshot error:', e); }
    }

    async function loadHardwareStats() {
      try {
        const showAll = document.getElementById('hwShowAll')?.checked;
        const activeOnly = !showAll;

        // Load both active and all stats in parallel
        const [resActive, resAll] = await Promise.all([
          authFetch('/admin/api/metrics/hardware?activeOnly=true'),
          authFetch('/admin/api/metrics/hardware?activeOnly=false')
        ]);
        const dActive = await resActive.json();
        const dAll = await resAll.json();

        // Update both counters
        document.getElementById('hwTotal').textContent = dActive.total || 0;
        document.getElementById('hwTotalAll').textContent = dAll.total || 0;

        // Use data based on toggle
        const d = activeOnly ? dActive : dAll;
        if (d.total === 0) return;

        const total = d.total;
        renderHwBars('hwOs', d.os, 5, total);
        renderHwBars('hwGpuVendor', d.gpu_vendor, 5, total);
        renderHwBars('hwGpuModel', d.gpu_model, 10, total);
        renderHwBars('hwResolution', d.resolution, 6, total);
        renderHwBars('hwRefreshRate', d.refresh_rate, 5, total);
        renderHwBars('hwCpuCores', d.cpu_cores, 6, total);
        renderHwBars('hwMemory', d.memory_gb, 6, total);
        renderHwBars('hwDisplayMode', d.display_mode, 4, total);
      } catch (e) { console.error('Hardware stats error:', e); }
    }

    async function loadAnalyticsStats() {
      try {
        const res = await authFetch('/admin/api/analytics');
        const d = await res.json();

        function updatePie(chart, data, limit = 10) {
          if (!chart || !data) return;
          const entries = Object.entries(data).filter(([k,v]) => v > 0).sort((a,b) => b[1] - a[1]).slice(0, limit);
          chart.data.labels = entries.map(([k]) => k);
          chart.data.datasets[0].data = entries.map(([,v]) => v);
          chart.update('none');
        }

        // Update session analytics from Redis aggregated data
        if (d.languages) updatePie(charts.language, d.languages);
        if (d.disconnectReasons) updatePie(charts.disconnect, d.disconnectReasons);

        // Update totals from daily stats
        if (d.daily) {
          document.getElementById('totalSessions').textContent = d.daily.sessions_ended || 0;
          document.getElementById('totalPlaytime').textContent = (d.daily.playtime_hours || 0) + 'h';
        }
      } catch (e) { console.error('Analytics stats error:', e); }
    }

    async function loadActivityChart() {
      if (!charts.activity) return;
      try {
        const [playersRes, serversRes, sessionsRes] = await Promise.all([
          authFetch('/admin/api/metrics/timeseries?metric=players&range=' + currentRange),
          authFetch('/admin/api/metrics/timeseries?metric=servers&range=' + currentRange),
          authFetch('/admin/api/metrics/timeseries?metric=sessions&range=' + currentRange)
        ]);
        const [playersData, serversData, sessionsData] = await Promise.all([
          playersRes.json(), serversRes.json(), sessionsRes.json()
        ]);

        const labels = playersData.points.map(p => {
          const dt = new Date(p.timestamp);
          return currentRange === '7d' ? dt.toLocaleDateString() : dt.toLocaleTimeString([], {hour:'2-digit',minute:'2-digit'});
        });

        charts.activity.data.labels = labels;
        charts.activity.data.datasets[0].data = playersData.points.map(p => p.value || 0);
        charts.activity.data.datasets[1].data = serversData.points.map(p => p.value || 0);
        charts.activity.data.datasets[2].data = sessionsData.points.map(p => p.value || 0);
        charts.activity.update('none');
      } catch (e) { console.error('Activity chart error:', e); }
    }

    async function loadFpsAndFrameTimeChart() {
      if (!charts.fpsLine) return;
      try {
        const [fpsRes, ftRes] = await Promise.all([
          authFetch('/admin/api/metrics/timeseries?metric=fps_avg&range=' + currentRange),
          authFetch('/admin/api/metrics/timeseries?metric=frame_time_p99&range=' + currentRange)
        ]);
        const [fpsData, ftData] = await Promise.all([fpsRes.json(), ftRes.json()]);
        const labels = fpsData.points.map(p => {
          const dt = new Date(p.timestamp);
          return currentRange === '7d' ? dt.toLocaleDateString() : dt.toLocaleTimeString([], {hour:'2-digit',minute:'2-digit'});
        });
        charts.fpsLine.data.labels = labels;
        charts.fpsLine.data.datasets[0].data = fpsData.points.map(p => p.value || 0);
        charts.fpsLine.data.datasets[1].data = ftData.points.map(p => p.value || 0);
        charts.fpsLine.update('none');
      } catch (e) { console.error('FPS chart error:', e); }
    }

    async function loadSessionDurationPercentilesChart() {
      if (!charts.sessionDurationLine) return;
      try {
        const [avgRes, p50Res, p90Res, p99Res] = await Promise.all([
          authFetch('/admin/api/metrics/timeseries?metric=session_duration_avg&range=' + currentRange),
          authFetch('/admin/api/metrics/timeseries?metric=session_duration_p50&range=' + currentRange),
          authFetch('/admin/api/metrics/timeseries?metric=session_duration_p90&range=' + currentRange),
          authFetch('/admin/api/metrics/timeseries?metric=session_duration_p99&range=' + currentRange)
        ]);
        const [avgData, p50Data, p90Data, p99Data] = await Promise.all([avgRes.json(), p50Res.json(), p90Res.json(), p99Res.json()]);
        const labels = avgData.points.map(p => {
          const dt = new Date(p.timestamp);
          return currentRange === '7d' ? dt.toLocaleDateString() : dt.toLocaleTimeString([], {hour:'2-digit',minute:'2-digit'});
        });
        // Convert seconds to minutes for display
        charts.sessionDurationLine.data.labels = labels;
        charts.sessionDurationLine.data.datasets[0].data = avgData.points.map(p => Math.round((p.value || 0) / 60));
        charts.sessionDurationLine.data.datasets[1].data = p50Data.points.map(p => Math.round((p.value || 0) / 60));
        charts.sessionDurationLine.data.datasets[2].data = p90Data.points.map(p => Math.round((p.value || 0) / 60));
        charts.sessionDurationLine.data.datasets[3].data = p99Data.points.map(p => Math.round((p.value || 0) / 60));
        charts.sessionDurationLine.update('none');
      } catch (e) { console.error('Session duration chart error:', e); }
    }

    async function refreshCharts() {
      await Promise.all([
        loadActivityChart(),
        loadTimeSeries('requests', charts.requests),
        loadTimeSeries('total_playtime_hours', charts.playtimeLine),
        // Multi-line charts
        loadFpsAndFrameTimeChart(),
        loadSessionDurationPercentilesChart(),
        // Single line charts
        loadTimeSeries('latency_avg', charts.latencyLine),
        loadTimeSeries('memory_avg', charts.memoryLine),
        loadTimeSeries('world_load_avg', charts.worldLoadLine),
        loadSnapshot(),
        loadHardwareStats(),
        loadAnalyticsStats()
      ]);
    }

    function setRange(range) {
      currentRange = range;
      document.querySelectorAll('.range-btn').forEach(b => b.classList.remove('active'));
      document.querySelector('[data-range="' + range + '"]')?.classList.add('active');
      refreshCharts();
    }

    function toggleAuto() {
      if (document.getElementById('autoRefresh').checked) {
        autoInterval = setInterval(refreshCharts, 30000);
      } else if (autoInterval) {
        clearInterval(autoInterval);
        autoInterval = null;
      }
    }

    document.querySelectorAll('.range-btn').forEach(btn => {
      btn.addEventListener('click', () => setRange(btn.dataset.range));
    });

    document.getElementById('loginForm').addEventListener('submit', async (e) => {
      e.preventDefault();
      const password = document.getElementById('loginPassword').value;
      try {
        const res = await fetch('/admin/login', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ password })
        });
        const data = await res.json();
        if (res.ok && data.token) {
          adminToken = data.token;
          savedPassword = password;
          localStorage.setItem('adminToken', adminToken);
          localStorage.setItem('adminPassword', password);
          init();
        } else {
          document.getElementById('loginError').textContent = data.error || 'Failed';
        }
      } catch (e) {
        document.getElementById('loginError').textContent = 'Connection error';
      }
    });

    async function init() {
      document.getElementById('loginOverlay').classList.add('hidden');
      document.getElementById('mainContent').classList.remove('hidden');
      loadStats();
      initCharts();
      refreshCharts();
      toggleAuto();
      setInterval(loadStats, 30000);
    }

    (async () => {
      if (await checkAuth()) init();
    })();
  </script>
</body>
</html>`;
    sendHtml(res, 200, html);
}

/**
 * Settings page - CDN download links and download stats
 */
function handleSettingsPage(req, res) {
    const html = `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Settings - Hytale Admin</title>
  <script src="https://cdn.jsdelivr.net/npm/chart.js"></script>
  <style>
    ${sharedStyles}
    .settings-section {
      background: rgba(255,255,255,0.03);
      border: 1px solid rgba(255,255,255,0.08);
      border-radius: 10px;
      padding: 20px;
      margin-bottom: 20px;
    }
    .settings-section h2 {
      color: #00d4ff;
      font-size: 1.2em;
      margin-bottom: 15px;
      padding-bottom: 10px;
      border-bottom: 1px solid rgba(255,255,255,0.1);
    }
    .link-row {
      display: grid;
      grid-template-columns: 150px 1fr 100px;
      gap: 15px;
      align-items: center;
      margin-bottom: 15px;
      padding: 15px;
      background: rgba(0,0,0,0.2);
      border-radius: 8px;
    }
    .link-row label {
      font-weight: 500;
      color: #b388ff;
    }
    .link-row input {
      width: 100%;
      padding: 10px 12px;
      background: rgba(0,0,0,0.3);
      border: 1px solid rgba(255,255,255,0.2);
      border-radius: 5px;
      color: #fff;
      font-family: monospace;
      font-size: 0.9em;
    }
    .link-row input:focus {
      outline: none;
      border-color: #00d4ff;
    }
    .link-stats {
      text-align: right;
      font-size: 0.85em;
    }
    .link-stats .count {
      color: #00d4ff;
      font-weight: bold;
      font-size: 1.2em;
    }
    .link-stats .label {
      color: #888;
    }
    .save-btn {
      background: linear-gradient(135deg, #00d4ff, #0099cc);
      border: none;
      color: #fff;
      padding: 12px 30px;
      border-radius: 8px;
      font-weight: bold;
      cursor: pointer;
      font-size: 1em;
    }
    .save-btn:disabled {
      opacity: 0.5;
      cursor: not-allowed;
    }
    .save-status {
      margin-left: 15px;
      color: #00ff88;
    }
    .chart-section {
      margin-top: 30px;
    }
    .download-chart {
      height: 300px;
      background: rgba(0,0,0,0.2);
      border-radius: 8px;
      padding: 15px;
    }
    .stats-grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
      gap: 15px;
      margin-bottom: 20px;
    }
    .stat-card {
      background: rgba(255,255,255,0.05);
      border: 1px solid rgba(255,255,255,0.1);
      border-radius: 10px;
      padding: 20px;
      text-align: center;
    }
    .stat-card .value {
      font-size: 2.5em;
      font-weight: bold;
      color: #00d4ff;
    }
    .stat-card .label {
      color: #888;
      font-size: 0.9em;
      margin-top: 5px;
    }
    .url-history {
      margin-top: 20px;
    }
    .url-history-item {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding: 10px 15px;
      background: rgba(0,0,0,0.2);
      border-radius: 5px;
      margin-bottom: 8px;
      font-family: monospace;
      font-size: 0.85em;
    }
    .url-history-item .url {
      color: #aaa;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
      max-width: 70%;
    }
    .url-history-item .count {
      color: #00d4ff;
      font-weight: bold;
    }
    .range-btns {
      display: flex;
      gap: 5px;
      margin-bottom: 15px;
    }
    .range-btn {
      padding: 6px 12px;
      background: rgba(255,255,255,0.1);
      border: 1px solid rgba(255,255,255,0.2);
      color: #888;
      border-radius: 4px;
      cursor: pointer;
      font-size: 0.85em;
    }
    .range-btn.active {
      background: rgba(0, 212, 255, 0.3);
      color: #00d4ff;
      border-color: rgba(0, 212, 255, 0.5);
    }
    .add-link-btn {
      background: rgba(0, 212, 255, 0.2);
      border: 1px dashed rgba(0, 212, 255, 0.4);
      color: #00d4ff;
      padding: 15px;
      border-radius: 8px;
      cursor: pointer;
      width: 100%;
      font-size: 1em;
      margin-bottom: 15px;
    }
    .add-link-btn:hover {
      background: rgba(0, 212, 255, 0.3);
    }
    .remove-btn {
      background: rgba(255, 100, 100, 0.2);
      border: 1px solid rgba(255, 100, 100, 0.3);
      color: #ff6b6b;
      padding: 8px 12px;
      border-radius: 5px;
      cursor: pointer;
      font-size: 0.85em;
    }
  </style>
</head>
<body>
  <div class="login-overlay" id="loginOverlay">
    <div class="login-box">
      <h2>Admin Login</h2>
      <form id="loginForm">
        <input type="password" id="loginPassword" placeholder="Password" required>
        <button type="submit" class="btn">Login</button>
      </form>
      <div class="login-error" id="loginError"></div>
    </div>
  </div>

  <div id="mainContent" class="hidden">
    ${navHtml('settings')}
    <div class="container">
      <!-- Download Stats Overview -->
      <div class="stats-grid">
        <div class="stat-card">
          <div class="value" id="totalDownloads">-</div>
          <div class="label">Total Downloads</div>
        </div>
        <div class="stat-card">
          <div class="value" id="jarDownloads">-</div>
          <div class="label">Server JAR Downloads</div>
        </div>
        <div class="stat-card">
          <div class="value" id="assetsDownloads">-</div>
          <div class="label">Assets.zip Downloads</div>
        </div>
      </div>

      <!-- CDN Links Settings -->
      <div class="settings-section">
        <h2>CDN Download Links</h2>
        <p style="color:#888;margin-bottom:20px;font-size:0.9em;">
          Configure external CDN links for downloads. Requests to <code>/download/{filename}</code> will redirect to these URLs.
          Metrics are tracked per URL - when you change a URL, downloads are counted separately.
        </p>

        <div id="linksContainer">
          <!-- Links will be populated by JS -->
        </div>

        <button class="add-link-btn" onclick="addLink()">+ Add Download Link</button>

        <div style="display:flex;align-items:center;margin-top:20px;">
          <button class="save-btn" id="saveBtn" onclick="saveLinks()">Save Changes</button>
          <span class="save-status" id="saveStatus"></span>
        </div>
      </div>

      <!-- Download History Chart -->
      <div class="settings-section chart-section">
        <h2>Download History</h2>
        <div class="range-btns">
          <button class="range-btn" data-range="24h" onclick="setRange('24h')">24h</button>
          <button class="range-btn active" data-range="7d" onclick="setRange('7d')">7 Days</button>
          <button class="range-btn" data-range="30d" onclick="setRange('30d')">30 Days</button>
        </div>
        <div class="download-chart">
          <canvas id="downloadChart"></canvas>
        </div>
      </div>

      <!-- URL History -->
      <div class="settings-section">
        <h2>Download URL History</h2>
        <p style="color:#888;margin-bottom:15px;font-size:0.9em;">
          All URLs that have been used for downloads, with their total download counts.
        </p>
        <div id="urlHistory">Loading...</div>
      </div>
    </div>
  </div>

  <script>
    ${sharedScripts}

    let downloadChart = null;
    let currentRange = '7d';
    let currentLinks = {};

    async function loadSettings() {
      try {
        const res = await authFetch('/admin/api/settings/downloads');
        const data = await res.json();
        currentLinks = data.links || {};
        renderLinks();
      } catch (e) {
        console.error('Error loading settings:', e);
      }
    }

    function renderLinks() {
      const container = document.getElementById('linksContainer');
      const entries = Object.entries(currentLinks);

      if (entries.length === 0) {
        container.innerHTML = '<div style="color:#888;padding:20px;text-align:center;">No download links configured. Add one below.</div>';
        return;
      }

      container.innerHTML = entries.map(([filename, url]) => \`
        <div class="link-row" data-filename="\${filename}">
          <label>\${filename}</label>
          <input type="text" value="\${url}" onchange="updateLink('\${filename}', this.value)" placeholder="https://cdn.example.com/path/to/file">
          <button class="remove-btn" onclick="removeLink('\${filename}')">Remove</button>
        </div>
      \`).join('');
    }

    function updateLink(filename, url) {
      currentLinks[filename] = url;
    }

    function removeLink(filename) {
      delete currentLinks[filename];
      renderLinks();
    }

    function addLink() {
      const filename = prompt('Enter filename (e.g., HytaleServer.jar):');
      if (!filename) return;
      if (currentLinks[filename]) {
        alert('This filename already exists!');
        return;
      }
      currentLinks[filename] = '';
      renderLinks();
      // Focus the new input
      setTimeout(() => {
        const input = document.querySelector(\`[data-filename="\${filename}"] input\`);
        if (input) input.focus();
      }, 100);
    }

    async function saveLinks() {
      const btn = document.getElementById('saveBtn');
      const status = document.getElementById('saveStatus');
      btn.disabled = true;
      status.textContent = 'Saving...';
      status.style.color = '#ffaa00';

      try {
        const res = await authFetch('/admin/api/settings/downloads', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ links: currentLinks })
        });
        const data = await res.json();
        if (data.success) {
          status.textContent = 'Saved!';
          status.style.color = '#00ff88';
          setTimeout(() => { status.textContent = ''; }, 2000);
        } else {
          status.textContent = 'Error: ' + (data.error || 'Unknown');
          status.style.color = '#ff6b6b';
        }
      } catch (e) {
        status.textContent = 'Error: ' + e.message;
        status.style.color = '#ff6b6b';
      }
      btn.disabled = false;
    }

    async function loadDownloadStats() {
      try {
        const res = await authFetch('/admin/api/settings/download-stats');
        const data = await res.json();

        document.getElementById('totalDownloads').textContent = data.total || 0;
        document.getElementById('jarDownloads').textContent = data.files?.['HytaleServer.jar']?.total || 0;
        document.getElementById('assetsDownloads').textContent = data.files?.['Assets.zip']?.total || 0;

        // Render URL history
        renderUrlHistory(data.files || {});
      } catch (e) {
        console.error('Error loading download stats:', e);
      }
    }

    function renderUrlHistory(files) {
      const container = document.getElementById('urlHistory');
      let html = '';

      for (const [filename, fileData] of Object.entries(files)) {
        if (!fileData.urls || Object.keys(fileData.urls).length === 0) continue;

        html += '<h4 style="color:#b388ff;margin:15px 0 10px;">' + filename + '</h4>';

        const sortedUrls = Object.entries(fileData.urls).sort((a, b) => b[1] - a[1]);
        html += sortedUrls.map(([url, count]) => \`
          <div class="url-history-item">
            <span class="url" title="\${url}">\${url}</span>
            <span class="count">\${count} downloads</span>
          </div>
        \`).join('');
      }

      container.innerHTML = html || '<div style="color:#888;">No download history yet</div>';
    }

    async function loadDownloadHistory() {
      try {
        const hours = currentRange === '24h' ? 24 : currentRange === '7d' ? 168 : 720;
        const res = await authFetch('/admin/api/settings/download-history?hours=' + hours);
        const data = await res.json();

        updateChart(data);
      } catch (e) {
        console.error('Error loading download history:', e);
      }
    }

    function initChart() {
      const ctx = document.getElementById('downloadChart').getContext('2d');
      downloadChart = new Chart(ctx, {
        type: 'line',
        data: {
          labels: [],
          datasets: []
        },
        options: {
          responsive: true,
          maintainAspectRatio: false,
          interaction: { mode: 'index', intersect: false },
          plugins: {
            legend: { display: true, labels: { color: '#888' } }
          },
          scales: {
            x: {
              grid: { color: 'rgba(255,255,255,0.1)' },
              ticks: { color: '#888', maxRotation: 45 }
            },
            y: {
              grid: { color: 'rgba(255,255,255,0.1)' },
              ticks: { color: '#888' },
              beginAtZero: true
            }
          }
        }
      });
    }

    function updateChart(data) {
      if (!downloadChart || !data) return;

      // Group by filename
      const colors = {
        'HytaleServer.jar': '#00d4ff',
        'Assets.zip': '#b388ff'
      };

      // Find all unique timestamps
      const allTimestamps = new Set();
      for (const item of data) {
        if (item.data) {
          item.data.forEach(p => allTimestamps.add(p.timestamp));
        }
      }

      const sortedTimestamps = Array.from(allTimestamps).sort((a, b) => a - b);
      const labels = sortedTimestamps.map(ts => {
        const dt = new Date(ts);
        return currentRange === '24h'
          ? dt.toLocaleTimeString([], {hour:'2-digit',minute:'2-digit'})
          : dt.toLocaleDateString([], {month:'short', day:'numeric'});
      });

      // Group data by filename
      const byFilename = {};
      for (const item of data) {
        // Extract filename from URL or use the URL as key
        let filename = item.url;
        if (item.url.includes('HytaleServer.jar')) filename = 'HytaleServer.jar';
        else if (item.url.includes('Assets.zip')) filename = 'Assets.zip';

        if (!byFilename[filename]) byFilename[filename] = {};

        if (item.data) {
          for (const point of item.data) {
            byFilename[filename][point.timestamp] = (byFilename[filename][point.timestamp] || 0) + point.count;
          }
        }
      }

      // Create datasets
      const datasets = Object.entries(byFilename).map(([filename, timestamps]) => ({
        label: filename,
        data: sortedTimestamps.map(ts => timestamps[ts] || 0),
        borderColor: colors[filename] || '#888',
        backgroundColor: (colors[filename] || '#888') + '33',
        fill: false,
        tension: 0.4,
        pointRadius: 2
      }));

      downloadChart.data.labels = labels;
      downloadChart.data.datasets = datasets;
      downloadChart.update('none');
    }

    function setRange(range) {
      currentRange = range;
      document.querySelectorAll('.range-btn').forEach(b => b.classList.remove('active'));
      document.querySelector('[data-range="' + range + '"]')?.classList.add('active');
      loadDownloadHistory();
    }

    document.getElementById('loginForm').addEventListener('submit', async (e) => {
      e.preventDefault();
      const password = document.getElementById('loginPassword').value;
      try {
        const res = await fetch('/admin/login', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ password })
        });
        const data = await res.json();
        if (res.ok && data.token) {
          adminToken = data.token;
          savedPassword = password;
          localStorage.setItem('adminToken', adminToken);
          localStorage.setItem('adminPassword', password);
          init();
        } else {
          document.getElementById('loginError').textContent = data.error || 'Failed';
        }
      } catch (e) {
        document.getElementById('loginError').textContent = 'Connection error';
      }
    });

    async function init() {
      document.getElementById('loginOverlay').classList.add('hidden');
      document.getElementById('mainContent').classList.remove('hidden');
      loadStats();
      initChart();
      await Promise.all([
        loadSettings(),
        loadDownloadStats(),
        loadDownloadHistory()
      ]);
      setInterval(loadStats, 30000);
    }

    (async () => {
      if (await checkAuth()) init();
    })();
  </script>
</body>
</html>`;
    sendHtml(res, 200, html);
}

module.exports = {
    handleServersPage,
    handlePlayersPage,
    handleLogsPage,
    handleMetricsPage,
    handleSettingsPage
};
