const http = require('http');
const cluster = require('cluster');
const crypto = require('crypto');

const config = require('./config');
const { redis, connect: connectRedis, isConnected } = require('./services/redis');
const storage = require('./services/storage');
const auth = require('./services/auth');
const assets = require('./services/assets');
const middleware = require('./middleware');
const { sendJson } = require('./utils/response');

// Route handlers
const routes = require('./routes');

/**
 * Main request handler
 */
async function handleRequest(req, res) {
  const timestamp = new Date().toISOString();

  // Skip logging for telemetry endpoints (too noisy)
  if (!req.url.includes('/telemetry')) {
    console.log(`${timestamp} ${req.method} ${req.url}`);
  }

  // CORS headers
  middleware.corsHeaders(res);

  // Handle OPTIONS preflight
  if (req.method === 'OPTIONS') {
    middleware.handleOptions(req, res);
    return;
  }

  // Parse URL
  const url = new URL(req.url, `http://${req.headers.host}`);
  const urlPath = url.pathname;

  // Handle binary uploads (like head-cache) before consuming body as string
  const headCacheMatch = urlPath.match(/^\/avatar\/([^/]+)\/head-cache$/);
  if (headCacheMatch && req.method === 'POST') {
    routes.avatar.handleAvatarRoutes(req, res, urlPath, {});
    return;
  }

  // Parse JSON body
  const body = await middleware.parseBody(req);

  // Extract user context
  const { uuid, name, tokenScope } = middleware.extractUserContext(body, req.headers);

  // Route the request
  await routeRequest(req, res, url, urlPath, body, uuid, name, tokenScope);
}

/**
 * Route request to appropriate handler
 */
async function routeRequest(req, res, url, urlPath, body, uuid, name, tokenScope) {
  const headers = req.headers;

  // Avatar viewer routes
  if (urlPath.startsWith('/avatar/')) {
    await routes.avatar.handleAvatarRoutes(req, res, urlPath, body);
    return;
  }

  // Customizer route
  if (urlPath.startsWith('/customizer')) {
    routes.avatar.handleCustomizerRoute(req, res, urlPath);
    return;
  }

  // Cosmetics list API
  if (urlPath === '/cosmetics/list') {
    routes.assets.handleCosmeticsList(req, res);
    return;
  }

  // Single cosmetic item data API
  if (urlPath.startsWith('/cosmetics/item/')) {
    routes.assets.handleCosmeticItem(req, res, urlPath);
    return;
  }

  // Static assets route
  if (urlPath.startsWith('/assets/')) {
    routes.assets.handleStaticAssets(req, res, urlPath);
    return;
  }

  // Asset extraction route
  if (urlPath.startsWith('/asset/')) {
    routes.assets.handleAssetRoute(req, res, urlPath);
    return;
  }

  // Health check
  if (urlPath === '/health' || urlPath === '/') {
    routes.health.handleHealth(req, res);
    return;
  }

  // Ignore favicon requests
  if (urlPath === '/favicon.ico') {
    res.writeHead(204);
    res.end();
    return;
  }

  // JWKS endpoint
  if (urlPath === '/.well-known/jwks.json' || urlPath === '/jwks.json') {
    routes.health.handleJwks(req, res);
    return;
  }

  // ====== Server Auto-Auth and OAuth endpoints (for F2P game servers) ======

  // Server auto-auth (instant token for F2P servers)
  if (urlPath === '/server/auto-auth') {
    routes.server.handleServerAutoAuth(req, res, body);
    return;
  }

  // Server game profiles (after OAuth)
  if (urlPath === '/server/game-profiles' || urlPath === '/game-profiles') {
    routes.server.handleServerGameProfiles(req, res, headers);
    return;
  }

  // OAuth device authorization endpoint
  if (urlPath === '/oauth2/device/auth') {
    routes.server.handleOAuthDeviceAuth(req, res, body);
    return;
  }

  // OAuth device verification page (user visits this)
  if (urlPath === '/oauth2/device/verify') {
    const query = Object.fromEntries(url.searchParams);
    routes.server.handleOAuthDeviceVerify(req, res, query);
    return;
  }

  // OAuth token endpoint (device code exchange, refresh)
  if (urlPath === '/oauth2/token') {
    routes.server.handleOAuthToken(req, res, body);
    return;
  }

  // ====== Game session endpoints ======

  // Game session endpoints
  if (urlPath === '/game-session/new') {
    routes.session.handleGameSessionNew(req, res, body, uuid, name);
    return;
  }

  if (urlPath === '/game-session/refresh') {
    await routes.session.handleGameSessionRefresh(req, res, body, uuid, name, headers);
    return;
  }

  if (urlPath === '/game-session/child' || urlPath.includes('/game-session/child')) {
    routes.session.handleGameSessionChild(req, res, body, uuid, name);
    return;
  }

  // Authorization grant endpoint
  if (urlPath === '/game-session/authorize' || urlPath.includes('/authorize') || urlPath.includes('/auth-grant')) {
    routes.session.handleAuthorizationGrant(req, res, body, uuid, name, headers);
    return;
  }

  // Token exchange endpoint
  if (urlPath === '/server-join/auth-token' || urlPath === '/game-session/exchange' || urlPath.includes('/auth-token')) {
    routes.session.handleTokenExchange(req, res, body, uuid, name, headers);
    return;
  }

  // Session/Auth endpoints (exclude admin paths)
  if ((urlPath.includes('/session') || urlPath.includes('/child')) && !urlPath.startsWith('/admin')) {
    routes.session.handleSession(req, res, body, uuid, name);
    return;
  }

  if (urlPath.includes('/auth')) {
    routes.session.handleAuth(req, res, body, uuid, name);
    return;
  }

  if (urlPath.includes('/token')) {
    routes.session.handleToken(req, res, body, uuid, name);
    return;
  }

  if (urlPath.includes('/validate') || urlPath.includes('/verify')) {
    routes.session.handleValidate(req, res, body, uuid, name);
    return;
  }

  if (urlPath.includes('/refresh')) {
    routes.session.handleRefresh(req, res, body, uuid, name);
    return;
  }

  // Account data endpoints
  if (urlPath === '/my-account/game-profile' || urlPath.includes('/game-profile')) {
    await routes.account.handleGameProfile(req, res, body, uuid, name);
    return;
  }

  if (urlPath === '/my-account/skin') {
    await routes.account.handleSkin(req, res, body, uuid, name, routes.avatar.invalidateHeadCache);
    return;
  }

  // Account-data skin endpoint (used by customizer) - extracts UUID from path
  if (urlPath.startsWith('/account-data/skin/')) {
    const skinUuid = urlPath.replace('/account-data/skin/', '');
    await routes.account.handleSkin(req, res, body, skinUuid, name, routes.avatar.invalidateHeadCache);
    return;
  }

  if (urlPath === '/my-account/cosmetics' || urlPath.includes('/my-account/cosmetics')) {
    routes.account.handleCosmetics(req, res, body, uuid, name);
    return;
  }

  if (urlPath === '/my-account/get-launcher-data') {
    routes.account.handleLauncherData(req, res, body, uuid, name);
    return;
  }

  if (urlPath === '/my-account/get-profiles') {
    routes.account.handleGetProfiles(req, res, body, uuid, name);
    return;
  }

  // Bug reports and feedback
  if (urlPath === '/bugs/create' || urlPath === '/feedback/create') {
    res.writeHead(204);
    res.end();
    return;
  }

  // Game session delete (logout/cleanup)
  if (urlPath === '/game-session' && req.method === 'DELETE') {
    await routes.session.handleGameSessionDelete(req, res, headers);
    return;
  }

  // Admin login endpoint (no auth required)
  if (urlPath === '/admin/login' && req.method === 'POST') {
    await routes.admin.handleAdminLogin(req, res, body);
    return;
  }

  // Admin verify endpoint
  if (urlPath === '/admin/verify') {
    const token = headers['x-admin-token'] || url.searchParams.get('token');
    await routes.admin.handleAdminVerify(req, res, token);
    return;
  }

  // Admin dashboard HTML page (no auth - login happens client-side)
  if (urlPath === '/admin' || urlPath === '/admin/') {
    routes.admin.handleAdminDashboard(req, res);
    return;
  }

  // Test page for head embed
  if (urlPath === '/test/head') {
    routes.avatar.handleTestHeadPage(req, res);
    return;
  }

  // Protected admin API routes - require token
  if (urlPath.startsWith('/admin/')) {
    const validToken = await middleware.verifyAdminAuth(headers);
    if (!validToken) {
      sendJson(res, 401, { error: 'Unauthorized. Please login at /admin' });
      return;
    }
  }

  // Active sessions API
  if (urlPath === '/admin/sessions' || urlPath === '/sessions/active') {
    await routes.admin.handleActiveSessions(req, res);
    return;
  }

  // Admin stats API
  if (urlPath === '/admin/stats') {
    await routes.admin.handleAdminStats(req, res);
    return;
  }

  // Admin servers API
  if (urlPath.startsWith('/admin/servers')) {
    await routes.admin.handleAdminServers(req, res, url);
    return;
  }

  // Player search API
  if (urlPath === '/admin/search') {
    await routes.admin.handlePlayerSearch(req, res, url);
    return;
  }

  // Pre-render queue
  if (urlPath === '/admin/prerender-queue') {
    await routes.admin.handlePrerenderQueue(req, res);
    return;
  }

  // Profile lookup by UUID
  if (urlPath.startsWith('/profile/uuid/')) {
    const lookupUuid = urlPath.replace('/profile/uuid/', '');
    await routes.account.handleProfileLookupByUuid(req, res, lookupUuid, headers);
    return;
  }

  // Profile lookup by username
  if (urlPath.startsWith('/profile/username/')) {
    const lookupUsername = decodeURIComponent(urlPath.replace('/profile/username/', ''));
    await routes.account.handleProfileLookupByUsername(req, res, lookupUsername, headers);
    return;
  }

  // Profile endpoint
  if (urlPath.includes('/profile') || urlPath.includes('/user') || urlPath.includes('/me')) {
    routes.account.handleProfile(req, res, body, uuid, name);
    return;
  }

  // Cosmetics endpoint
  if (urlPath.includes('/cosmetic') || urlPath.includes('/unlocked') || urlPath.includes('/inventory')) {
    routes.account.handleCosmetics(req, res, body, uuid, name);
    return;
  }

  // Telemetry endpoint
  if (urlPath.includes('/telemetry') || urlPath.includes('/analytics') || urlPath.includes('/event')) {
    sendJson(res, 200, { success: true, received: true });
    return;
  }

  // Catch-all - return comprehensive response that might satisfy various requests
  console.log(`Unknown endpoint: ${urlPath}`);
  const requestHost = req.headers.host;
  const authGrant = auth.generateAuthorizationGrant(uuid, name, crypto.randomUUID(), null, requestHost);
  const accessToken = auth.generateIdentityToken(uuid, name, null, ['game.base'], requestHost);
  sendJson(res, 200, {
    success: true,
    identityToken: accessToken,
    sessionToken: auth.generateSessionToken(uuid, requestHost),
    authorizationGrant: authGrant,
    accessToken: accessToken,
    tokenType: 'Bearer',
    user: { uuid, name, premium: true }
  });
}

/**
 * Initialize and start the server
 */
async function startServer() {
  console.log('=== Hytale Auth Server ===');
  console.log(`Domain: ${config.domain}`);
  console.log(`Data directory: ${config.dataDir}`);
  console.log(`Assets path: ${config.assetsPath}`);

  // Pre-load cosmetics
  assets.preloadCosmetics();

  // Connect to Redis
  await connectRedis();

  // Create HTTP server
  const server = http.createServer(handleRequest);
  server.listen(config.port, '0.0.0.0', () => {
    const workerId = cluster.isWorker ? `Worker ${cluster.worker.id}` : 'Main';
    console.log(`[${workerId}] Server running on port ${config.port}`);
    console.log(`[${workerId}] Redis: ${isConnected() ? 'connected' : 'NOT CONNECTED'}`);

    // Only show endpoints once (first worker or single process)
    if (!cluster.isWorker || cluster.worker.id === 1) {
      console.log(`Endpoints:`);
      // For domains > 10 chars, use unified endpoint; otherwise show subdomains
      if (config.domain.length > 10) {
        console.log(`  - ${config.domain} (unified endpoint)`);
      } else {
        console.log(`  - sessions.${config.domain}`);
        console.log(`  - account-data.${config.domain}`);
        console.log(`  - telemetry.${config.domain}`);
      }
      console.log(`  - Avatar viewer: /avatar/{uuid}`);
      console.log(`  - Avatar customizer: /customizer/{uuid}`);
      console.log(`  - Cosmetics list: /cosmetics/list`);
      console.log(`  - Asset extraction: /asset/{path}`);
      console.log(`  - Admin dashboard: /admin`);
      console.log(`  - Admin API: /admin/sessions, /admin/stats`);
      console.log(`  - Server auto-auth: /server/auto-auth`);
      console.log(`  - OAuth device flow: /oauth2/device/auth, /oauth2/token`);
    }
  });
}

/**
 * Run with clustering support
 */
function run() {
  if (cluster.isPrimary && config.workers > 1) {
    console.log(`Primary ${process.pid} starting ${config.workers} workers...`);

    for (let i = 0; i < config.workers; i++) {
      cluster.fork();
    }

    cluster.on('exit', (worker, code, signal) => {
      console.log(`Worker ${worker.process.pid} died (${signal || code}). Restarting...`);
      cluster.fork();
    });

    cluster.on('online', (worker) => {
      console.log(`Worker ${worker.process.pid} is online`);
    });
  } else {
    // Worker process or single-process mode
    startServer().catch(err => {
      console.error('Failed to start server:', err);
      process.exit(1);
    });
  }
}

module.exports = { run, startServer };

// Run if executed directly
if (require.main === module) {
  run();
}
