const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const config = require('../config');
const storage = require('../services/storage');
const metrics = require('../services/metrics');
const requestLogger = require('../services/requestLogger');
const { sendJson, sendHtml } = require('../utils/response');

// Head cache directory for prerender queue check
const HEAD_CACHE_DIR = config.headCacheDir;

/**
 * Admin login endpoint
 */
async function handleAdminLogin(req, res, body) {
  const { password } = body;

  if (!password) {
    sendJson(res, 400, { error: 'Password required' });
    return;
  }

  if (password !== config.adminPassword) {
    sendJson(res, 401, { error: 'Invalid password' });
    return;
  }

  const token = crypto.randomBytes(32).toString('hex');
  const success = await storage.createAdminToken(token);

  if (!success) {
    sendJson(res, 500, { error: 'Failed to create session' });
    return;
  }

  console.log(`Admin login successful, token: ${token.substring(0, 8)}...`);
  sendJson(res, 200, { token });
}

/**
 * Admin verify endpoint
 */
async function handleAdminVerify(req, res, token) {
  if (!token) {
    sendJson(res, 401, { valid: false });
    return;
  }
  const valid = await storage.verifyAdminToken(token);
  sendJson(res, valid ? 200 : 401, { valid });
}

/**
 * Active sessions API
 */
async function handleActiveSessions(req, res) {
  const data = await storage.getAllActiveSessions();
  sendJson(res, 200, data);
}

/**
 * Admin stats API (lightweight summary) - matches server.js format
 */
async function handleAdminStats(req, res) {
  const keyCounts = await storage.getKeyCounts();
  const redisConnected = storage.isRedisConnected();

  sendJson(res, 200, {
    uptime: process.uptime(),
    memory: process.memoryUsage(),
    redis: { connected: redisConnected },
    keys: keyCounts,
    activeSessions: keyCounts.sessions,
    activeServers: keyCounts.servers,
    activePlayers: keyCounts.activePlayers || 0,
    timestamp: new Date().toISOString()
  });
}

/**
 * Admin servers API (paginated server list with players)
 */
async function handleAdminServers(req, res, url) {
  const page = parseInt(url.searchParams.get('page')) || 1;
  const limit = Math.min(parseInt(url.searchParams.get('limit')) || 10, 50);
  const activeOnly = url.searchParams.get('all') !== 'true'; // Default to active only

  const result = await storage.getPaginatedServers(page, limit, activeOnly);
  result.activeOnly = activeOnly;
  sendJson(res, 200, result);
}

/**
 * Admin cleanup API - remove stale servers and players
 */
async function handleAdminCleanup(req, res) {
  const result = await storage.cleanupStaleData();
  sendJson(res, 200, result);
}

/**
 * Admin data counts API - get active vs total counts
 */
async function handleAdminDataCounts(req, res) {
  const counts = await storage.getDataCounts();
  sendJson(res, 200, counts);
}

/**
 * Search players by username or UUID (server-side)
 */
async function handlePlayerSearch(req, res, url) {
  const query = url.searchParams.get('q') || '';
  const limit = Math.min(parseInt(url.searchParams.get('limit')) || 50, 100);

  if (!query || query.length < 2) {
    sendJson(res, 200, { results: [], query: '' });
    return;
  }

  const results = await storage.searchPlayers(query, limit);
  sendJson(res, 200, { results, query });
}

/**
 * Get prerender queue stats (optimized - counts only, no UUID list)
 */
async function handlePrerenderQueue(req, res) {
  // Count total cached files on disk
  let totalCachedFiles = 0;
  try {
    if (fs.existsSync(HEAD_CACHE_DIR)) {
      const files = fs.readdirSync(HEAD_CACHE_DIR);
      totalCachedFiles = files.filter(f => f.endsWith('.png')).length;
    }
  } catch (e) {
    // Ignore errors reading cache dir
  }

  if (!storage.isRedisConnected()) {
    sendJson(res, 200, { totalCachedFiles, totalPlayers: 0, error: 'Redis not connected' });
    return;
  }

  try {
    // Just get player count from stats (fast)
    const keyCounts = await storage.getKeyCounts();
    const uncached = Math.max(0, keyCounts.activePlayers - totalCachedFiles);

    sendJson(res, 200, {
      total: keyCounts.activePlayers,
      totalPlayers: keyCounts.activePlayers,
      cached: totalCachedFiles,
      totalCachedFiles,
      uncached  // Dashboard expects this field name
    });
  } catch (e) {
    console.error('Error getting prerender queue:', e.message);
    sendJson(res, 500, { error: e.message });
  }
}

/**
 * Admin dashboard HTML page - tabbed layout with active/all data filtering
 */
function handleAdminDashboard(req, res) {
  const html = `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Hytale Auth Server - Admin Dashboard</title>
  <style>
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body {
      font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
      background: linear-gradient(135deg, #1a1a2e 0%, #16213e 100%);
      color: #e0e0e0;
      min-height: 100vh;
    }
    .container { max-width: 1400px; margin: 0 auto; padding: 20px; }
    h1 {
      text-align: center;
      color: #00d4ff;
      margin-bottom: 20px;
      font-size: 2em;
      text-shadow: 0 0 20px rgba(0, 212, 255, 0.3);
    }
    /* Nav tabs */
    .nav-tabs {
      display: flex;
      gap: 5px;
      background: rgba(0, 0, 0, 0.3);
      padding: 10px;
      border-radius: 12px 12px 0 0;
      margin-bottom: 0;
    }
    .nav-tab {
      padding: 12px 24px;
      background: rgba(255, 255, 255, 0.05);
      border: 1px solid rgba(255, 255, 255, 0.1);
      color: #888;
      border-radius: 8px 8px 0 0;
      cursor: pointer;
      font-size: 1em;
      font-weight: 500;
      transition: all 0.2s;
    }
    .nav-tab:hover { background: rgba(255, 255, 255, 0.1); color: #ccc; }
    .nav-tab.active {
      background: rgba(0, 212, 255, 0.2);
      border-color: rgba(0, 212, 255, 0.3);
      color: #00d4ff;
      border-bottom-color: transparent;
    }
    .tab-content {
      background: rgba(255, 255, 255, 0.03);
      border: 1px solid rgba(255, 255, 255, 0.08);
      border-top: none;
      border-radius: 0 0 12px 12px;
      padding: 20px;
      min-height: 500px;
    }
    .tab-pane { display: none; }
    .tab-pane.active { display: block; }
    /* Stats grid */
    .stats-grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
      gap: 15px;
      margin-bottom: 20px;
    }
    .stat-card {
      background: rgba(255, 255, 255, 0.05);
      border: 1px solid rgba(255, 255, 255, 0.1);
      border-radius: 10px;
      padding: 15px;
      text-align: center;
    }
    .stat-value {
      font-size: 2em;
      font-weight: bold;
      color: #00d4ff;
    }
    .stat-label { color: #888; font-size: 0.85em; }
    .stat-card.launcher { border-color: rgba(138, 43, 226, 0.3); }
    .stat-card.launcher .stat-value { color: #b388ff; }
    /* Toggle switch */
    .toggle-row {
      display: flex;
      align-items: center;
      gap: 15px;
      margin-bottom: 15px;
      padding: 10px 15px;
      background: rgba(0, 0, 0, 0.2);
      border-radius: 8px;
    }
    .toggle-label { color: #888; font-size: 0.9em; }
    .toggle-switch {
      position: relative;
      width: 50px;
      height: 26px;
    }
    .toggle-switch input { opacity: 0; width: 0; height: 0; }
    .toggle-slider {
      position: absolute;
      cursor: pointer;
      top: 0; left: 0; right: 0; bottom: 0;
      background: rgba(255, 255, 255, 0.1);
      border-radius: 26px;
      transition: 0.3s;
    }
    .toggle-slider:before {
      position: absolute;
      content: "";
      height: 20px;
      width: 20px;
      left: 3px;
      bottom: 3px;
      background: #888;
      border-radius: 50%;
      transition: 0.3s;
    }
    .toggle-switch input:checked + .toggle-slider { background: rgba(0, 212, 255, 0.4); }
    .toggle-switch input:checked + .toggle-slider:before { transform: translateX(24px); background: #00d4ff; }
    .data-counts { color: #666; font-size: 0.85em; }
    .cleanup-btn {
      background: rgba(255, 100, 100, 0.2);
      border: 1px solid rgba(255, 100, 100, 0.3);
      color: #ff6b6b;
      padding: 8px 16px;
      border-radius: 5px;
      cursor: pointer;
      font-size: 0.85em;
      margin-left: auto;
    }
    .cleanup-btn:hover { background: rgba(255, 100, 100, 0.3); }
    /* Server/Player cards */
    .server-card {
      background: rgba(0, 212, 255, 0.08);
      border: 1px solid rgba(0, 212, 255, 0.2);
      border-radius: 8px;
      padding: 15px;
      margin-bottom: 12px;
    }
    .server-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-bottom: 8px;
    }
    .server-name { font-weight: bold; color: #00d4ff; font-size: 1.05em; }
    .server-meta { font-family: monospace; font-size: 0.75em; color: #666; }
    .server-version {
      font-size: 0.75em; color: #888;
      background: rgba(255, 255, 255, 0.05);
      padding: 2px 8px; border-radius: 10px; margin-left: 8px;
    }
    .server-ip { color: #b388ff; }
    .player-count {
      background: #00d4ff; color: #1a1a2e;
      padding: 4px 12px; border-radius: 20px;
      font-weight: bold; font-size: 0.85em;
    }
    .players-list {
      display: flex; flex-wrap: wrap; gap: 8px;
      margin-top: 10px; padding-top: 10px;
      border-top: 1px solid rgba(255, 255, 255, 0.1);
    }
    .player-tag {
      background: rgba(255, 255, 255, 0.08);
      padding: 8px 12px; border-radius: 12px;
      display: flex; align-items: center; gap: 10px;
    }
    .player-avatar {
      width: 36px; height: 36px; border-radius: 50%;
      background: rgba(0, 0, 0, 0.3); border: none;
    }
    .player-info { display: flex; flex-direction: column; gap: 2px; }
    .player-name { color: #fff; font-weight: 500; font-size: 0.9em; }
    .player-uuid { color: #666; font-size: 0.7em; font-family: monospace; }
    .player-state { display: flex; gap: 8px; font-size: 0.7em; color: #888; }
    .state-item { display: flex; align-items: center; gap: 3px; }
    .state-item.good { color: #5f5; }
    .state-item.warn { color: #fc5; }
    .state-item.bad { color: #f55; }
    .ttl-badge {
      font-size: 0.7em; padding: 2px 6px; border-radius: 10px;
      margin-left: 5px; font-weight: normal;
    }
    .ttl-fresh { background: rgba(0, 255, 136, 0.25); color: #7fdfb0; }
    .ttl-warning { background: rgba(255, 170, 0, 0.25); color: #d4a852; }
    .ttl-critical { background: rgba(255, 68, 68, 0.25); color: #e08080; }
    /* Pagination */
    .pagination {
      display: flex; justify-content: center; align-items: center;
      gap: 15px; margin-top: 20px; padding: 15px;
    }
    .pagination button {
      background: rgba(0, 212, 255, 0.2);
      border: 1px solid rgba(0, 212, 255, 0.3);
      color: #00d4ff; padding: 8px 16px; border-radius: 5px; cursor: pointer;
    }
    .pagination button:hover:not(:disabled) { background: rgba(0, 212, 255, 0.3); }
    .pagination button:disabled { opacity: 0.4; cursor: not-allowed; }
    .pagination span { color: #888; font-size: 0.9em; }
    /* Search */
    .search-box {
      display: flex; gap: 10px; margin-bottom: 15px;
    }
    .search-box input {
      flex: 1; padding: 10px 15px; border-radius: 8px;
      border: 1px solid rgba(255, 255, 255, 0.1);
      background: rgba(0, 0, 0, 0.3); color: #fff; font-size: 1em;
    }
    .search-box button {
      padding: 10px 20px; background: linear-gradient(135deg, #00d4ff, #0099cc);
      border: none; border-radius: 8px; color: #fff; font-weight: bold; cursor: pointer;
    }
    .search-results { margin-top: 15px; }
    .search-result {
      background: rgba(0, 212, 255, 0.08);
      border: 1px solid rgba(0, 212, 255, 0.2);
      border-radius: 8px; padding: 12px; margin-bottom: 10px;
    }
    .search-result .player-header {
      display: flex; align-items: center; gap: 12px; margin-bottom: 8px;
    }
    .search-result .player-avatar-lg {
      width: 50px; height: 50px; border-radius: 50%; border: none;
    }
    .search-result h3 { color: #00d4ff; font-size: 1.1em; margin: 0; }
    .search-result .uuid-full { color: #888; font-size: 0.8em; font-family: monospace; }
    .search-result .server-list { margin-top: 10px; padding-left: 15px; }
    .search-result .server-item { color: #aaa; font-size: 0.9em; margin: 5px 0; }
    .search-result .server-link { color: #00d4ff; cursor: pointer; text-decoration: underline; }
    .no-data { color: #666; font-style: italic; padding: 40px; text-align: center; }
    /* Logs */
    .logs-controls {
      display: flex; gap: 10px; align-items: center; margin-bottom: 15px; flex-wrap: wrap;
    }
    .logs-controls input, .logs-controls select {
      padding: 8px 12px; border-radius: 5px;
      border: 1px solid #333; background: #1a1a2e; color: #fff; font-size: 0.9em;
    }
    .logs-controls input[type="text"] { flex: 1; min-width: 200px; }
    .logs-stats {
      display: flex; gap: 15px; margin-bottom: 15px; font-size: 0.85em; color: #888; flex-wrap: wrap;
    }
    .logs-stats span { background: rgba(255,255,255,0.05); padding: 5px 12px; border-radius: 15px; }
    .logs-container {
      max-height: 500px; overflow-y: auto;
      font-family: 'Monaco', 'Menlo', monospace; font-size: 0.8em;
      background: #0d0d1a; border-radius: 8px; padding: 10px;
    }
    .log-entry {
      padding: 8px 10px; border-bottom: 1px solid rgba(255,255,255,0.05);
      display: grid; grid-template-columns: 160px 60px 45px auto 80px; gap: 10px; align-items: start;
      cursor: pointer;
    }
    .log-entry:hover { background: rgba(0, 212, 255, 0.1); }
    .log-entry.expanded { display: block; background: rgba(0, 212, 255, 0.05); }
    .log-timestamp { color: #666; font-size: 0.85em; }
    .log-method { font-weight: bold; padding: 2px 6px; border-radius: 3px; text-align: center; font-size: 0.8em; }
    .log-method.GET { background: rgba(0, 200, 100, 0.3); color: #5f5; }
    .log-method.POST { background: rgba(0, 150, 255, 0.3); color: #5af; }
    .log-method.DELETE { background: rgba(255, 100, 100, 0.3); color: #f88; }
    .log-method.OPTIONS { background: rgba(150, 150, 150, 0.3); color: #aaa; }
    .log-status { text-align: center; }
    .log-status.s2xx { color: #5f5; }
    .log-status.s4xx { color: #fc5; }
    .log-status.s5xx { color: #f55; }
    .log-url { color: #e0e0e0; word-break: break-all; }
    .log-time { color: #888; text-align: right; font-size: 0.85em; }
    .log-details {
      margin-top: 10px; padding: 10px; background: rgba(0,0,0,0.3);
      border-radius: 5px; white-space: pre-wrap; word-break: break-all; font-size: 0.9em; color: #aaa;
    }
    /* Status & Login */
    .status-bar {
      display: flex; align-items: center; gap: 15px; padding: 10px 15px;
      background: rgba(0, 0, 0, 0.3); border-radius: 8px; margin-bottom: 20px;
    }
    .status-dot { width: 10px; height: 10px; border-radius: 50%; display: inline-block; margin-right: 5px; }
    .status-dot.online { background: #00ff88; box-shadow: 0 0 10px #00ff88; }
    .status-dot.offline { background: #ff4444; }
    .login-overlay {
      position: fixed; top: 0; left: 0; right: 0; bottom: 0;
      background: rgba(0, 0, 0, 0.9); display: flex;
      justify-content: center; align-items: center; z-index: 1000;
    }
    .login-box {
      background: linear-gradient(135deg, #1a1a2e 0%, #16213e 100%);
      border: 1px solid rgba(0, 212, 255, 0.3);
      border-radius: 12px; padding: 40px; text-align: center; max-width: 400px; width: 90%;
    }
    .login-box h2 { color: #00d4ff; margin-bottom: 20px; }
    .login-box input {
      width: 100%; padding: 12px; border-radius: 5px;
      border: 1px solid #333; background: #0d0d1a; color: #fff; margin-bottom: 15px; font-size: 1em;
    }
    .login-box button {
      width: 100%; padding: 12px;
      background: linear-gradient(135deg, #00d4ff, #0099cc);
      border: none; border-radius: 5px; color: #fff; font-size: 1em; font-weight: bold; cursor: pointer;
    }
    .login-error { color: #ff6b6b; margin-top: 10px; font-size: 0.9em; }
    .logout-btn {
      background: rgba(255, 100, 100, 0.2); border: 1px solid rgba(255, 100, 100, 0.3);
      color: #ff6b6b; padding: 6px 12px; border-radius: 5px; cursor: pointer; font-size: 0.8em;
    }
    .hidden { display: none !important; }
    .refresh-btn {
      background: linear-gradient(135deg, #00d4ff, #0099cc);
      border: none; color: #fff; padding: 8px 20px; border-radius: 20px;
      cursor: pointer; font-weight: bold;
    }
    .refresh-btn:disabled { opacity: 0.5; cursor: not-allowed; }
    .last-update { color: #666; font-size: 0.8em; }
  </style>
</head>
<body>
  <!-- Login Overlay -->
  <div class="login-overlay" id="loginOverlay">
    <div class="login-box">
      <h2>Admin Login</h2>
      <form id="loginForm">
        <input type="password" id="loginPassword" placeholder="Enter admin password" autocomplete="current-password" required>
        <button type="submit">Login</button>
      </form>
      <div class="login-error" id="loginError"></div>
    </div>
  </div>

  <div class="container hidden" id="mainContent">
    <h1>Hytale Auth Server</h1>

    <!-- Status bar -->
    <div class="status-bar">
      <span><span class="status-dot" id="redisStatus"></span><span id="redisText">Redis</span></span>
      <span class="last-update">Updated: <span id="lastUpdate">-</span></span>
      <button class="refresh-btn" id="refreshBtn" onclick="refreshData()">Refresh</button>
      <button class="logout-btn" onclick="logout()">Logout</button>
    </div>

    <!-- Stats -->
    <div class="stats-grid">
      <div class="stat-card launcher"><div class="stat-value" id="launcherOnline">-</div><div class="stat-label">Launcher Online</div></div>
      <div class="stat-card"><div class="stat-value" id="playerCount">-</div><div class="stat-label">Active Players</div></div>
      <div class="stat-card"><div class="stat-value" id="serverCount">-</div><div class="stat-label">Active Servers</div></div>
      <div class="stat-card"><div class="stat-value" id="sessionCount">-</div><div class="stat-label">Sessions</div></div>
      <div class="stat-card"><div class="stat-value" id="userCount">-</div><div class="stat-label">Users</div></div>
      <div class="stat-card"><div class="stat-value" id="prerenderCached">-</div><div class="stat-label">Heads Cached</div></div>
    </div>

    <!-- Navigation tabs -->
    <div class="nav-tabs">
      <button class="nav-tab active" onclick="switchTab('servers')">Servers</button>
      <button class="nav-tab" onclick="switchTab('players')">Players</button>
      <button class="nav-tab" onclick="switchTab('logs')">Logs</button>
    </div>

    <div class="tab-content">
      <!-- Servers Tab -->
      <div class="tab-pane active" id="tab-servers">
        <div class="toggle-row">
          <label class="toggle-switch">
            <input type="checkbox" id="showAllServers" onchange="loadServers(1)">
            <span class="toggle-slider"></span>
          </label>
          <span class="toggle-label">Show all servers (including inactive)</span>
          <span class="data-counts" id="serverCounts">Loading...</span>
          <button class="cleanup-btn" onclick="runCleanup()">Cleanup Stale Data</button>
        </div>
        <div id="serversList">Loading...</div>
      </div>

      <!-- Players Tab -->
      <div class="tab-pane" id="tab-players">
        <div class="search-box">
          <input type="text" id="playerSearch" placeholder="Search by username or UUID..." onkeyup="if(event.key==='Enter')searchPlayers()">
          <button onclick="searchPlayers()">Search</button>
          <button onclick="clearSearch()" style="background: #555;">Clear</button>
        </div>
        <div id="searchResults" class="search-results"></div>
      </div>

      <!-- Logs Tab -->
      <div class="tab-pane" id="tab-logs">
        <div class="logs-stats" id="logsStats">Loading stats...</div>
        <div class="logs-controls">
          <input type="text" id="logFilter" placeholder="Filter logs (URL, IP, method...)" onkeyup="if(event.key==='Enter')loadLogs()">
          <select id="logLines" onchange="loadLogs()">
            <option value="50">50 lines</option>
            <option value="100" selected>100 lines</option>
            <option value="200">200 lines</option>
            <option value="500">500 lines</option>
          </select>
          <select id="logMethod" onchange="loadLogs()">
            <option value="">All methods</option>
            <option value="GET">GET</option>
            <option value="POST">POST</option>
            <option value="DELETE">DELETE</option>
          </select>
          <button class="refresh-btn" onclick="loadLogs()">Load Logs</button>
          <label style="display: flex; align-items: center; gap: 5px; color: #888; font-size: 0.85em;">
            <input type="checkbox" id="logsAutoRefresh" onchange="toggleLogsAutoRefresh()"> Auto
          </label>
        </div>
        <div class="logs-container" id="logsContainer">
          <div class="no-data">Click "Load Logs" to view</div>
        </div>
      </div>
    </div>
  </div>

  <script>
    let currentPage = 1;
    const pageLimit = 10;
    let adminToken = localStorage.getItem('adminToken');
    let savedPassword = localStorage.getItem('adminPassword');
    let logsAutoRefreshInterval = null;

    // Auth helpers
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

    document.getElementById('loginForm').addEventListener('submit', async (e) => {
      e.preventDefault();
      const password = document.getElementById('loginPassword').value;
      const errorDiv = document.getElementById('loginError');
      errorDiv.textContent = '';
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
          showDashboard();
        } else {
          errorDiv.textContent = data.error || 'Login failed';
        }
      } catch (e) { errorDiv.textContent = 'Connection error'; }
    });

    function logout() {
      adminToken = null;
      savedPassword = null;
      localStorage.removeItem('adminToken');
      localStorage.removeItem('adminPassword');
      document.getElementById('loginOverlay').classList.remove('hidden');
      document.getElementById('mainContent').classList.add('hidden');
      // Reset tab states
      loadedTabs = { servers: false, players: false, logs: false };
    }

    async function showDashboard() {
      document.getElementById('loginOverlay').classList.add('hidden');
      document.getElementById('mainContent').classList.remove('hidden');
      // Load stats first
      await refreshStats();
      // Load servers tab (default)
      loadServers(1);
      loadedTabs.servers = true;
    }

    (async () => {
      if (await checkAuth()) showDashboard();
      else { adminToken = null; localStorage.removeItem('adminToken'); }
    })();

    // Tab switching - lazy load data
    let loadedTabs = { servers: false, players: false, logs: false };
    let currentTab = 'servers';

    function switchTab(tabName) {
      document.querySelectorAll('.nav-tab').forEach(t => t.classList.remove('active'));
      document.querySelectorAll('.tab-pane').forEach(p => p.classList.remove('active'));
      document.querySelector(\`[onclick="switchTab('\${tabName}')"]\`).classList.add('active');
      document.getElementById('tab-' + tabName).classList.add('active');
      currentTab = tabName;

      // Lazy load tab data
      if (tabName === 'servers' && !loadedTabs.servers) {
        loadServers(1);
        loadedTabs.servers = true;
      } else if (tabName === 'players' && !loadedTabs.players) {
        document.getElementById('searchResults').innerHTML = '<div class="no-data">Enter a search term above</div>';
        loadedTabs.players = true;
      } else if (tabName === 'logs' && !loadedTabs.logs) {
        loadLogsStats();
        loadedTabs.logs = true;
      }
    }

    // TTL helper
    function getTtlStatus(ttl) {
      const h = ttl / 3600;
      if (h > 5) return { class: 'ttl-fresh', text: Math.round(h) + 'h' };
      if (h > 1) return { class: 'ttl-warning', text: (Math.round(h * 10) / 10) + 'h' };
      if (ttl > 60) return { class: 'ttl-critical', text: Math.round(ttl / 60) + 'm' };
      return { class: 'ttl-critical', text: ttl + 's' };
    }

    // Refresh stats only (lightweight)
    async function refreshStats() {
      try {
        // Launcher stats
        try {
          const lr = await fetch('https://api.hytalef2p.com/api/players/stats');
          const l = await lr.json();
          document.getElementById('launcherOnline').textContent = l.online || 0;
        } catch (e) { document.getElementById('launcherOnline').textContent = '?'; }

        // Main stats
        const sr = await authFetch('/admin/stats');
        const s = await sr.json();
        document.getElementById('playerCount').textContent = s.activePlayers || 0;
        document.getElementById('serverCount').textContent = s.activeServers || 0;
        document.getElementById('sessionCount').textContent = s.activeSessions || 0;
        document.getElementById('userCount').textContent = s.keys?.users || 0;

        const rs = document.getElementById('redisStatus');
        const rt = document.getElementById('redisText');
        if (s.redis?.connected) {
          rs.className = 'status-dot online';
          rt.textContent = 'Redis Connected';
        } else {
          rs.className = 'status-dot offline';
          rt.textContent = 'Redis Disconnected';
        }

        // Prerender
        try {
          const pr = await authFetch('/admin/prerender-queue');
          const p = await pr.json();
          document.getElementById('prerenderCached').textContent = p.cached || 0;
        } catch (e) { document.getElementById('prerenderCached').textContent = '?'; }

        // Data counts for servers tab
        try {
          const cr = await authFetch('/admin/counts');
          const c = await cr.json();
          document.getElementById('serverCounts').textContent =
            \`Active: \${c.activeServers} / Total: \${c.totalServers} servers | Active: \${c.activePlayers} / Total: \${c.totalPlayers} players\`;
        } catch (e) {}

        document.getElementById('lastUpdate').textContent = new Date().toLocaleTimeString();
      } catch (e) { console.error(e); }
    }

    // Refresh current tab data
    async function refreshData() {
      const btn = document.getElementById('refreshBtn');
      btn.disabled = true;
      btn.textContent = '...';
      try {
        await refreshStats();

        // Reload current tab data
        if (currentTab === 'servers') {
          await loadServers(currentPage);
        } else if (currentTab === 'logs') {
          const container = document.getElementById('logsContainer');
          if (container.logsData) await loadLogs();
          else await loadLogsStats();
        }
        // Players tab only refreshes on search
      } catch (e) { console.error(e); }
      finally { btn.disabled = false; btn.textContent = 'Refresh'; }
    }

    // Load servers
    async function loadServers(page) {
      const list = document.getElementById('serversList');
      list.innerHTML = '<div class="no-data">Loading...</div>';
      const showAll = document.getElementById('showAllServers').checked;
      try {
        const r = await authFetch(\`/admin/servers?page=\${page}&limit=\${pageLimit}\${showAll ? '&all=true' : ''}\`);
        const d = await r.json();
        if (!d.servers || d.servers.length === 0) {
          list.innerHTML = '<div class="no-data">No ' + (showAll ? '' : 'active ') + 'servers found</div>';
          return;
        }
        let html = d.servers.map(srv => {
          const minTtl = srv.players?.length ? Math.min(...srv.players.map(p => p.ttl || 0)) : 0;
          const ttlS = getTtlStatus(minTtl);
          return \`
          <div class="server-card">
            <div class="server-header">
              <span class="server-name">
                \${srv.name || srv.audience}
                \${srv.version ? \`<span class="server-version">v\${srv.version}</span>\` : ''}
                <span class="ttl-badge \${ttlS.class}">\${ttlS.text}</span>
              </span>
              <span class="player-count">\${srv.playerCount} player\${srv.playerCount !== 1 ? 's' : ''}</span>
            </div>
            <div class="server-meta">ID: \${srv.audience}\${srv.ip ? \` | <span class="server-ip">\${srv.ip}</span>\` : ''}</div>
            \${srv.players?.length ? \`
            <div class="players-list">
              \${srv.players.map(p => {
                const pt = getTtlStatus(p.ttl || 0);
                const st = p.state || {};
                const fc = st.fps > 50 ? 'good' : st.fps > 30 ? 'warn' : st.fps ? 'bad' : '';
                const lc = st.latency < 50 ? 'good' : st.latency < 100 ? 'warn' : st.latency ? 'bad' : '';
                return \`
                <div class="player-tag">
                  <iframe class="player-avatar" src="/avatar/\${p.uuid}/head?bg=black" loading="lazy"></iframe>
                  <div class="player-info">
                    <span class="player-name">\${p.username} <span class="ttl-badge \${pt.class}">\${pt.text}</span></span>
                    <span class="player-uuid">\${p.uuid.substring(0,8)}...</span>
                    \${st.fps || st.latency ? \`
                    <div class="player-state">
                      \${st.fps ? \`<span class="state-item \${fc}">\${Math.round(st.fps)} FPS</span>\` : ''}
                      \${st.latency ? \`<span class="state-item \${lc}">\${Math.round(st.latency)}ms</span>\` : ''}
                    </div>\` : ''}
                  </div>
                </div>\`;
              }).join('')}
            </div>\` : ''}
          </div>\`;
        }).join('');
        const pg = d.pagination;
        html += \`
          <div class="pagination">
            <button onclick="loadServers(\${pg.page - 1})" \${!pg.hasPrev ? 'disabled' : ''}>Prev</button>
            <span>Page \${pg.page} / \${pg.totalPages} (\${pg.totalServers} servers)</span>
            <button onclick="loadServers(\${pg.page + 1})" \${!pg.hasNext ? 'disabled' : ''}>Next</button>
          </div>\`;
        list.innerHTML = html;
        currentPage = page;
      } catch (e) { list.innerHTML = '<div class="no-data">Error: ' + e.message + '</div>'; }
    }

    // Cleanup
    async function runCleanup() {
      if (!confirm('Remove all stale servers and players?')) return;
      try {
        const r = await authFetch('/admin/cleanup');
        const d = await r.json();
        alert(\`Cleaned: \${d.servers} servers, \${d.players} players\`);
        refreshData();
      } catch (e) { alert('Cleanup failed: ' + e.message); }
    }

    // Player search
    async function searchPlayers() {
      const q = document.getElementById('playerSearch').value.trim();
      const res = document.getElementById('searchResults');
      if (q.length < 2) { res.innerHTML = '<div class="no-data">Enter at least 2 characters</div>'; return; }
      res.innerHTML = '<div class="no-data">Searching...</div>';
      try {
        const r = await authFetch(\`/admin/search?q=\${encodeURIComponent(q)}&limit=50\`);
        const d = await r.json();
        if (!d.results?.length) { res.innerHTML = '<div class="no-data">No players found</div>'; return; }
        res.innerHTML = d.results.map(p => {
          const pt = getTtlStatus(p.ttl || 0);
          const st = p.state || {};
          return \`
          <div class="search-result">
            <div class="player-header">
              <iframe class="player-avatar-lg" src="/avatar/\${p.uuid}/head?bg=black" loading="lazy"></iframe>
              <div>
                <h3>\${p.username} <span class="ttl-badge \${pt.class}">\${pt.text}</span></h3>
                <div class="uuid-full">\${p.uuid}</div>
                \${st.fps ? \`<div class="player-state"><span class="state-item">\${Math.round(st.fps)} FPS</span></div>\` : ''}
              </div>
            </div>
            <div class="server-list">
              <strong>Playing on:</strong>
              \${p.servers.map(s => \`<div class="server-item">\${s.name || s.audience}</div>\`).join('')}
            </div>
          </div>\`;
        }).join('');
      } catch (e) { res.innerHTML = '<div class="no-data">Error: ' + e.message + '</div>'; }
    }

    function clearSearch() {
      document.getElementById('playerSearch').value = '';
      document.getElementById('searchResults').innerHTML = '';
    }

    // Logs
    async function loadLogsStats() {
      try {
        const r = await authFetch('/admin/logs/stats');
        const s = await r.json();
        document.getElementById('logsStats').innerHTML = s.exists
          ? \`<span>\${s.sizeHuman}</span><span>\${s.lines.toLocaleString()} lines</span>\`
          : '<span>No log file</span>';
      } catch (e) { document.getElementById('logsStats').innerHTML = '<span>Error</span>'; }
    }

    async function loadLogs() {
      const c = document.getElementById('logsContainer');
      c.innerHTML = '<div class="no-data">Loading...</div>';
      const f = document.getElementById('logFilter').value;
      const n = document.getElementById('logLines').value;
      const m = document.getElementById('logMethod').value;
      try {
        const r = await authFetch(\`/admin/logs?lines=\${n}\${f ? '&filter=' + encodeURIComponent(f) : ''}\`);
        const d = await r.json();
        let logs = d.logs || [];
        if (m) logs = logs.filter(l => l.method === m);
        if (!logs.length) { c.innerHTML = '<div class="no-data">No logs</div>'; return; }
        c.innerHTML = logs.map((l, i) => {
          const sc = l.statusCode ? 's' + Math.floor(l.statusCode / 100) + 'xx' : '';
          const ts = l.timestamp ? new Date(l.timestamp).toLocaleString() : '-';
          const su = l.url?.length > 50 ? l.url.substring(0, 50) + '...' : l.url;
          return \`<div class="log-entry" onclick="toggleLog(this, \${i})" data-i="\${i}">
            <span class="log-timestamp">\${ts}</span>
            <span class="log-method \${l.method}">\${l.method || '-'}</span>
            <span class="log-status \${sc}">\${l.statusCode || '-'}</span>
            <span class="log-url">\${su || '-'}</span>
            <span class="log-time">\${l.responseTime || '-'}</span>
          </div>\`;
        }).join('');
        c.logsData = logs;
        loadLogsStats();
      } catch (e) { c.innerHTML = '<div class="no-data">Error: ' + e.message + '</div>'; }
    }

    function toggleLog(el, i) {
      const c = document.getElementById('logsContainer');
      const l = c.logsData?.[i];
      if (!l) return;
      if (el.classList.contains('expanded')) {
        el.classList.remove('expanded');
        el.querySelector('.log-details')?.remove();
        return;
      }
      c.querySelectorAll('.expanded').forEach(x => { x.classList.remove('expanded'); x.querySelector('.log-details')?.remove(); });
      el.classList.add('expanded');
      const d = document.createElement('div');
      d.className = 'log-details';
      d.innerHTML = \`URL: \${l.url}\\nIP: \${l.ip}\\nUA: \${l.userAgent}\\nHost: \${l.host}\\n\${l.body ? 'Body: ' + JSON.stringify(l.body, null, 2) : ''}\`;
      el.appendChild(d);
    }

    function toggleLogsAutoRefresh() {
      if (document.getElementById('logsAutoRefresh').checked) {
        loadLogs();
        logsAutoRefreshInterval = setInterval(loadLogs, 5000);
      } else if (logsAutoRefreshInterval) {
        clearInterval(logsAutoRefreshInterval);
        logsAutoRefreshInterval = null;
      }
    }

    // Auto-refresh stats only (lightweight), not full tab data
    setInterval(refreshStats, 60000);
  </script>
</body>
</html>`;

  sendHtml(res, 200, html);
}

/**
 * Admin logs API - view request logs
 * Supports: ?lines=N (default 100), ?filter=pattern, ?offset=N
 */
async function handleAdminLogs(req, res, url) {
  const lines = Math.min(parseInt(url.searchParams.get('lines')) || 100, 1000);
  const offset = parseInt(url.searchParams.get('offset')) || 0;
  const filter = url.searchParams.get('filter') || '';
  const format = url.searchParams.get('format') || 'json'; // json or raw

  const logFile = requestLogger.LOG_FILE;

  if (!fs.existsSync(logFile)) {
    sendJson(res, 200, {
      logs: [],
      total: 0,
      file: logFile,
      message: 'No log file found'
    });
    return;
  }

  try {
    const content = fs.readFileSync(logFile, 'utf8');
    let logLines = content.trim().split('\n').filter(line => line.trim());

    // Apply filter if provided
    if (filter) {
      const filterLower = filter.toLowerCase();
      logLines = logLines.filter(line => line.toLowerCase().includes(filterLower));
    }

    const total = logLines.length;

    // Get most recent lines (end of file), with optional offset
    const startIndex = Math.max(0, logLines.length - lines - offset);
    const endIndex = logLines.length - offset;
    logLines = logLines.slice(startIndex, endIndex);

    if (format === 'raw') {
      res.writeHead(200, { 'Content-Type': 'text/plain' });
      res.end(logLines.join('\n'));
      return;
    }

    // Parse JSON log entries
    const parsedLogs = logLines.map(line => {
      try {
        return JSON.parse(line);
      } catch (e) {
        return { raw: line };
      }
    }).reverse(); // Most recent first

    sendJson(res, 200, {
      logs: parsedLogs,
      total,
      returned: parsedLogs.length,
      offset,
      file: logFile,
      filter: filter || null
    });
  } catch (e) {
    sendJson(res, 500, { error: e.message });
  }
}

/**
 * Admin logs stats - get log file info
 */
async function handleAdminLogsStats(req, res) {
  const logFile = requestLogger.LOG_FILE;
  const logDir = requestLogger.LOG_DIR;

  const stats = {
    logFile,
    logDir,
    exists: false,
    size: 0,
    sizeHuman: '0 B',
    lines: 0,
    rotatedFiles: []
  };

  try {
    if (fs.existsSync(logFile)) {
      const fileStat = fs.statSync(logFile);
      stats.exists = true;
      stats.size = fileStat.size;
      stats.sizeHuman = formatBytes(fileStat.size);
      stats.modified = fileStat.mtime.toISOString();

      // Count lines
      const content = fs.readFileSync(logFile, 'utf8');
      stats.lines = content.trim().split('\n').filter(l => l.trim()).length;
    }

    // Check for rotated files
    for (let i = 1; i <= 5; i++) {
      const rotatedFile = `${logFile}.${i}`;
      if (fs.existsSync(rotatedFile)) {
        const rotatedStat = fs.statSync(rotatedFile);
        stats.rotatedFiles.push({
          file: rotatedFile,
          size: rotatedStat.size,
          sizeHuman: formatBytes(rotatedStat.size),
          modified: rotatedStat.mtime.toISOString()
        });
      }
    }
  } catch (e) {
    stats.error = e.message;
  }

  sendJson(res, 200, stats);
}

function formatBytes(bytes) {
  if (bytes === 0) return '0 B';
  const k = 1024;
  const sizes = ['B', 'KB', 'MB', 'GB'];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
}

// ============================================================================
// OPTIMIZED APIs (use new storage functions with caching/sorted sets)
// ============================================================================

/**
 * Active servers API - OPTIMIZED
 */
async function handleActiveServersApi(req, res, url) {
  const page = parseInt(url.searchParams.get('page')) || 1;
  const limit = Math.min(parseInt(url.searchParams.get('limit')) || 20, 50);

  const result = await storage.getActiveServers(page, limit);
  sendJson(res, 200, result);
}

/**
 * Active players API - OPTIMIZED
 */
async function handleActivePlayersApi(req, res, url) {
  const page = parseInt(url.searchParams.get('page')) || 1;
  const limit = Math.min(parseInt(url.searchParams.get('limit')) || 50, 100);

  const result = await storage.getActivePlayers(page, limit);
  sendJson(res, 200, result);
}

/**
 * Prometheus metrics endpoint
 */
async function handlePrometheusMetrics(req, res) {
  const metricsText = await metrics.getPrometheusMetrics();
  res.writeHead(200, {
    'Content-Type': 'text/plain; version=0.0.4; charset=utf-8'
  });
  res.end(metricsText);
}

/**
 * Metrics time-series API - queries VictoriaMetrics
 */
async function handleMetricsTimeSeries(req, res, url) {
  const metric = url.searchParams.get('metric') || 'players';
  const range = url.searchParams.get('range') || '1h';

  const data = await metrics.getMetricsFromVM(metric, range);
  sendJson(res, 200, data);
}

/**
 * Metrics snapshot API
 */
async function handleMetricsSnapshot(req, res) {
  const snapshot = await metrics.getMetricsSnapshot();
  sendJson(res, 200, snapshot);
}

/**
 * Hardware stats API - aggregated hardware info from players
 * Query params:
 *   - activeOnly=true (default) - only currently active players
 *   - activeOnly=false - all players who ever sent telemetry
 */
async function handleHardwareStats(req, res) {
  const url = new URL(req.url, `http://${req.headers.host}`);
  const activeOnly = url.searchParams.get('activeOnly') !== 'false';
  const stats = await metrics.getHardwareStats(activeOnly);
  sendJson(res, 200, stats || { total: 0, totalWithHardware: 0, os: {}, gpu_vendor: {}, resolution: {}, cpu_cores: {}, memory_ranges: {} });
}

/**
 * Analytics stats API - session end data, events, distributions
 */
async function handleAnalyticsStats(req, res) {
  const stats = await storage.getAnalyticsStats();
  sendJson(res, 200, stats);
}

// ============================================================================
// SETTINGS APIs (CDN download links, etc.)
// ============================================================================

/**
 * Get download links settings
 */
async function handleGetDownloadLinks(req, res) {
  const links = await storage.getDownloadLinks();
  sendJson(res, 200, { links });
}

/**
 * Save download links settings
 */
async function handleSaveDownloadLinks(req, res, body) {
  if (!body || !body.links) {
    sendJson(res, 400, { error: 'Missing links in request body' });
    return;
  }

  const settings = await storage.getSettings();
  settings.downloadLinks = body.links;
  const success = await storage.saveSettings(settings);

  if (success) {
    console.log('Download links updated:', Object.keys(body.links));
    sendJson(res, 200, { success: true });
  } else {
    sendJson(res, 500, { error: 'Failed to save settings' });
  }
}

/**
 * Get download statistics
 */
async function handleGetDownloadStats(req, res) {
  const stats = await storage.getDownloadStats();
  sendJson(res, 200, stats);
}

/**
 * Get download history for charts
 */
async function handleGetDownloadHistory(req, res, url) {
  const hours = parseInt(url.searchParams.get('hours')) || 168; // Default 7 days
  const filename = url.searchParams.get('filename'); // Optional filter

  // Get history for all files
  const files = ['HytaleServer.jar', 'Assets.zip'];
  const allHistory = [];

  for (const file of files) {
    if (filename && file !== filename) continue;
    const history = await storage.getDownloadHistory(file, null, hours);
    allHistory.push(...history);
  }

  sendJson(res, 200, allHistory);
}

module.exports = {
  handleAdminLogin,
  handleAdminVerify,
  handleActiveSessions,
  handleAdminStats,
  handleAdminServers,
  handlePlayerSearch,
  handlePrerenderQueue,
  handleAdminDashboard,
  handleAdminLogs,
  handleAdminLogsStats,
  handleAdminCleanup,
  handleAdminDataCounts,
  // Optimized APIs
  handleActiveServersApi,
  handleActivePlayersApi,
  // Metrics
  handlePrometheusMetrics,
  handleMetricsTimeSeries,
  handleMetricsSnapshot,
  handleHardwareStats,
  handleAnalyticsStats,
  // Settings
  handleGetDownloadLinks,
  handleSaveDownloadLinks,
  handleGetDownloadStats,
  handleGetDownloadHistory,
};
