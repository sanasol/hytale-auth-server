const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { execSync } = require('child_process');

// Configuration via environment variables
const PORT = process.env.PORT || 3000;
const DOMAIN = process.env.DOMAIN || 'sanasol.ws';
const DATA_DIR = process.env.DATA_DIR || '/app/data';
const ASSETS_PATH = process.env.ASSETS_PATH || '/app/assets/Assets.zip';

// File path for persisted user data
const USER_DATA_FILE = path.join(DATA_DIR, 'user_data.json');

// Cache for cosmetics loaded from Assets.zip
let cachedCosmetics = null;

// Ed25519 key pair for JWT signing - persisted to survive restarts
const KEY_ID = '2025-10-01';
const KEY_FILE = path.join(DATA_DIR, 'jwt_keys.json');

let privateKey, publicKey, publicKeyJwk;

function loadOrGenerateKeys() {
  try {
    // Try to load existing keys
    if (fs.existsSync(KEY_FILE)) {
      const keyData = JSON.parse(fs.readFileSync(KEY_FILE, 'utf8'));
      privateKey = crypto.createPrivateKey({
        key: Buffer.from(keyData.privateKey, 'base64'),
        format: 'der',
        type: 'pkcs8'
      });
      publicKey = crypto.createPublicKey({
        key: Buffer.from(keyData.publicKey, 'base64'),
        format: 'der',
        type: 'spki'
      });
      publicKeyJwk = publicKey.export({ format: 'jwk' });
      console.log('Loaded existing Ed25519 key pair from disk');
      return;
    }
  } catch (e) {
    console.log('Could not load existing keys:', e.message);
  }

  // Generate new keys
  const keyPair = crypto.generateKeyPairSync('ed25519');
  privateKey = keyPair.privateKey;
  publicKey = keyPair.publicKey;
  publicKeyJwk = publicKey.export({ format: 'jwk' });

  // Save keys to disk
  try {
    const dir = path.dirname(KEY_FILE);
    if (!fs.existsSync(dir)) {
      fs.mkdirSync(dir, { recursive: true });
    }
    const keyData = {
      privateKey: privateKey.export({ format: 'der', type: 'pkcs8' }).toString('base64'),
      publicKey: publicKey.export({ format: 'der', type: 'spki' }).toString('base64'),
      createdAt: new Date().toISOString()
    };
    fs.writeFileSync(KEY_FILE, JSON.stringify(keyData, null, 2));
    console.log('Generated and saved new Ed25519 key pair');
  } catch (e) {
    console.log('Could not save keys:', e.message);
    console.log('Generated Ed25519 key pair (not persisted)');
  }
}

loadOrGenerateKeys();

// Load persisted user data
function loadUserData() {
  try {
    if (fs.existsSync(USER_DATA_FILE)) {
      return JSON.parse(fs.readFileSync(USER_DATA_FILE, 'utf8'));
    }
  } catch (e) {
    console.log('Notice: Could not load user data:', e.message);
  }
  return {};
}

// Save user data to disk
function saveUserData(data) {
  try {
    // Ensure data directory exists
    const dir = path.dirname(USER_DATA_FILE);
    if (!fs.existsSync(dir)) {
      fs.mkdirSync(dir, { recursive: true });
    }
    fs.writeFileSync(USER_DATA_FILE, JSON.stringify(data, null, 2));
  } catch (e) {
    console.log('Notice: Could not save user data:', e.message);
  }
}

// In-memory cache, loaded from file on startup
let userData = loadUserData();

// Load cosmetics from Assets.zip
function loadCosmeticsFromAssets() {
  if (cachedCosmetics) {
    return cachedCosmetics;
  }

  if (!fs.existsSync(ASSETS_PATH)) {
    console.log('Assets.zip not found at:', ASSETS_PATH);
    return null;
  }

  console.log('Loading cosmetics from:', ASSETS_PATH);

  // Map of JSON file names to cosmetic category names
  const categoryMap = {
    'BodyCharacteristics.json': 'bodyCharacteristic',
    'Capes.json': 'cape',
    'EarAccessory.json': 'earAccessory',
    'Ears.json': 'ears',
    'Eyebrows.json': 'eyebrows',
    'Eyes.json': 'eyes',
    'Faces.json': 'face',
    'FaceAccessory.json': 'faceAccessory',
    'FacialHair.json': 'facialHair',
    'Gloves.json': 'gloves',
    'Haircuts.json': 'haircut',
    'HeadAccessory.json': 'headAccessory',
    'Mouths.json': 'mouth',
    'Overpants.json': 'overpants',
    'Overtops.json': 'overtop',
    'Pants.json': 'pants',
    'Shoes.json': 'shoes',
    'SkinFeatures.json': 'skinFeature',
    'Undertops.json': 'undertop',
    'Underwear.json': 'underwear',
  };

  const cosmetics = {};

  for (const [fileName, categoryName] of Object.entries(categoryMap)) {
    const entryPath = `Cosmetics/CharacterCreator/${fileName}`;

    try {
      // Use unzip -p to extract file content to stdout
      const content = execSync(`unzip -p "${ASSETS_PATH}" "${entryPath}"`, {
        encoding: 'utf8',
        maxBuffer: 10 * 1024 * 1024
      });

      const items = JSON.parse(content);
      const ids = items
        .filter(item => item && item.Id)
        .map(item => item.Id);

      if (ids.length > 0) {
        cosmetics[categoryName] = ids;
        console.log(`  Loaded ${ids.length} ${categoryName} items`);
      }
    } catch (e) {
      // File might not exist or parse error - silently skip
    }
  }

  if (Object.keys(cosmetics).length > 0) {
    cachedCosmetics = cosmetics;
    console.log('Cosmetics loaded successfully from Assets.zip');
    return cosmetics;
  }

  console.log('No cosmetics loaded from Assets.zip');
  return null;
}

// Generate a JWT token with proper Ed25519 signing
function generateToken(payload) {
  const header = Buffer.from(JSON.stringify({
    alg: 'EdDSA',
    kid: KEY_ID,
    typ: 'JWT'
  })).toString('base64url');
  const body = Buffer.from(JSON.stringify(payload)).toString('base64url');
  const signingInput = `${header}.${body}`;

  // Sign with Ed25519 private key
  const signature = crypto.sign(null, Buffer.from(signingInput), privateKey);
  return `${signingInput}.${signature.toString('base64url')}`;
}

// Generate identity token for the game client/server
function generateIdentityToken(uuid, name, entitlements = ['game.base']) {
  const now = Math.floor(Date.now() / 1000);
  const exp = now + 36000; // 10 hours

  return generateToken({
    sub: uuid,
    name: name,
    username: name,
    entitlements: entitlements,
    scope: 'hytale:server hytale:client',
    iat: now,
    exp: exp,
    iss: `https://sessions.${DOMAIN}`,
    jti: crypto.randomUUID()
  });
}

// Generate session token for the game server
function generateSessionToken(uuid) {
  const now = Math.floor(Date.now() / 1000);
  const exp = now + 36000; // 10 hours

  return generateToken({
    sub: uuid,
    scope: 'hytale:server',
    iat: now,
    exp: exp,
    iss: `https://sessions.${DOMAIN}`,
    jti: crypto.randomUUID()
  });
}

function handleRequest(req, res) {
  const timestamp = new Date().toISOString();
  // Skip logging for telemetry endpoints (too noisy)
  if (!req.url.includes('/telemetry')) {
    console.log(`${timestamp} ${req.method} ${req.url}`);
  }

  // CORS headers
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, PUT, DELETE, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type, Authorization');

  if (req.method === 'OPTIONS') {
    res.writeHead(200);
    res.end();
    return;
  }

  // Parse URL
  const url = new URL(req.url, `http://${req.headers.host}`);

  // Collect body for POST requests
  let body = '';
  req.on('data', chunk => { body += chunk; });
  req.on('end', () => {
    try {
      const jsonBody = body ? JSON.parse(body) : {};
      routeRequest(req, res, url, jsonBody, req.headers);
    } catch (e) {
      routeRequest(req, res, url, {}, req.headers);
    }
  });
}

function parseJwtPayload(token) {
  if (!token || typeof token !== 'string') return null;
  const parts = token.split('.');
  if (parts.length < 2) return null;
  try {
    return JSON.parse(Buffer.from(parts[1], 'base64url').toString());
  } catch (e) {
    return null;
  }
}

function extractJwtFromHeaders(headers) {
  if (!headers || !headers.authorization) return null;
  const value = String(headers.authorization).trim();
  if (!value) return null;
  if (value.toLowerCase().startsWith('bearer ')) {
    return value.slice(7).trim();
  }
  return value;
}

function extractJwtFromBody(body) {
  if (!body || typeof body !== 'object') return null;
  const fields = [
    'identityToken',
    'identity_token',
    'sessionToken',
    'session_token',
    'accessToken',
    'access_token',
    'authorizationGrant',
    'authorization_grant'
  ];
  for (const field of fields) {
    if (typeof body[field] === 'string' && body[field].includes('.')) {
      return body[field];
    }
  }
  return null;
}

function normalizeIssuerToUrl(issuer) {
  if (!issuer || typeof issuer !== 'string') return null;
  const hasScheme = /^[a-zA-Z][a-zA-Z\d+-.]*:/.test(issuer);
  const raw = hasScheme ? issuer : `https://${issuer}`;
  try {
    return new URL(raw);
  } catch (e) {
    return null;
  }
}

function buildIssuerRedirectUrl(issuer, req) {
  const parsed = normalizeIssuerToUrl(issuer);
  if (!parsed) return null;
  const targetHost = parsed.host.toLowerCase();
  const currentHost = (req.headers.host || '').toLowerCase();
  if (currentHost && currentHost === targetHost) return null;
  const protocol = parsed.protocol === 'http:' || parsed.protocol === 'https:' ? parsed.protocol : 'https:';
  return `${protocol}//${parsed.host}${req.url}`;
}

function maybeRedirectToIssuer(req, res, body, headers) {
  const token = extractJwtFromHeaders(headers) || extractJwtFromBody(body);
  if (!token) return false;
  const payload = parseJwtPayload(token);
  const issuer = payload && typeof payload.iss === 'string' ? payload.iss : null;
  if (!issuer) return false;
  const targetUrl = buildIssuerRedirectUrl(issuer, req);
  if (!targetUrl) return false;
  res.writeHead(307, { Location: targetUrl });
  res.end();
  console.log(`Redirected request to issuer: ${targetUrl}`);
  return true;
}

function routeRequest(req, res, url, body, headers) {
  const urlPath = url.pathname;

  if (maybeRedirectToIssuer(req, res, body, headers)) {
    return;
  }

  // Extract UUID from Authorization header if present
  let uuid = body.uuid || crypto.randomUUID();
  let name = body.name || 'Player';

  if (headers && headers.authorization) {
    try {
      const token = headers.authorization.replace('Bearer ', '');
      const parts = token.split('.');
      if (parts.length >= 2) {
        const payload = JSON.parse(Buffer.from(parts[1], 'base64url').toString());
        if (payload.sub) uuid = payload.sub;
        if (payload.name) name = payload.name;
      }
    } catch (e) {}
  }

  // Health check
  if (urlPath === '/health' || urlPath === '/') {
    sendJson(res, 200, { status: 'ok', server: 'hytale-auth', domain: DOMAIN });
    return;
  }

  // Ignore favicon requests
  if (urlPath === '/favicon.ico') {
    res.writeHead(204);
    res.end();
    return;
  }

  // JWKS endpoint for JWT signature verification
  if (urlPath === '/.well-known/jwks.json' || urlPath === '/jwks.json') {
    sendJson(res, 200, {
      keys: [{
        kty: publicKeyJwk.kty,
        crv: publicKeyJwk.crv,
        x: publicKeyJwk.x,
        kid: KEY_ID,
        use: 'sig',
        alg: 'EdDSA'
      }]
    });
    return;
  }

  // Game session endpoints
  if (urlPath === '/game-session/new') {
    handleGameSessionNew(req, res, body, uuid, name);
    return;
  }

  if (urlPath === '/game-session/refresh') {
    handleGameSessionRefresh(req, res, body, uuid, name, headers);
    return;
  }

  if (urlPath === '/game-session/child' || urlPath.includes('/game-session/child')) {
    handleGameSessionChild(req, res, body, uuid, name);
    return;
  }

  // Authorization grant endpoint - server requests this to authorize a client connection
  if (urlPath === '/game-session/authorize' || urlPath.includes('/authorize') || urlPath.includes('/auth-grant')) {
    handleAuthorizationGrant(req, res, body, uuid, name, headers);
    return;
  }

  // Token exchange endpoint - client exchanges auth grant for access token
  if (urlPath === '/server-join/auth-token' || urlPath === '/game-session/exchange' || urlPath.includes('/auth-token')) {
    handleTokenExchange(req, res, body, uuid, name, headers);
    return;
  }

  // Session/Auth endpoints
  if (urlPath.includes('/session') || urlPath.includes('/child')) {
    handleSession(req, res, body, uuid, name);
    return;
  }

  if (urlPath.includes('/auth')) {
    handleAuth(req, res, body, uuid, name);
    return;
  }

  if (urlPath.includes('/token')) {
    handleToken(req, res, body, uuid, name);
    return;
  }

  if (urlPath.includes('/validate') || urlPath.includes('/verify')) {
    handleValidate(req, res, body, uuid, name);
    return;
  }

  if (urlPath.includes('/refresh')) {
    handleRefresh(req, res, body, uuid, name);
    return;
  }

  // Account data endpoints
  if (urlPath === '/my-account/game-profile' || urlPath.includes('/game-profile')) {
    handleGameProfile(req, res, body, uuid, name);
    return;
  }

  if (urlPath === '/my-account/skin') {
    handleSkin(req, res, body, uuid, name);
    return;
  }

  if (urlPath === '/my-account/cosmetics' || urlPath.includes('/my-account/cosmetics')) {
    handleCosmetics(req, res, body, uuid, name);
    return;
  }

  if (urlPath === '/my-account/get-launcher-data') {
    handleLauncherData(req, res, body, uuid, name);
    return;
  }

  if (urlPath === '/my-account/get-profiles') {
    handleGetProfiles(req, res, body, uuid, name);
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
    res.writeHead(204);
    res.end();
    return;
  }

  // Profile endpoint
  if (urlPath.includes('/profile') || urlPath.includes('/user') || urlPath.includes('/me')) {
    handleProfile(req, res, body, uuid, name);
    return;
  }

  // Cosmetics endpoint
  if (urlPath.includes('/cosmetic') || urlPath.includes('/unlocked') || urlPath.includes('/inventory')) {
    handleCosmetics(req, res, body, uuid, name);
    return;
  }

  // Telemetry endpoint
  if (urlPath.includes('/telemetry') || urlPath.includes('/analytics') || urlPath.includes('/event')) {
    sendJson(res, 200, { success: true, received: true });
    return;
  }

  // Catch-all - return comprehensive response that might satisfy various requests
  console.log(`Unknown endpoint: ${urlPath}`);
  const authGrant = generateAuthorizationGrant(uuid, name, crypto.randomUUID());
  const accessToken = generateIdentityToken(uuid, name);
  sendJson(res, 200, {
    success: true,
    identityToken: accessToken,
    sessionToken: generateSessionToken(uuid),
    authorizationGrant: authGrant,
    accessToken: accessToken,
    tokenType: 'Bearer',
    user: { uuid, name, premium: true }
  });
}

// Generate authorization grant token for server connection
function generateAuthorizationGrant(uuid, name, audience) {
  const now = Math.floor(Date.now() / 1000);
  const exp = now + 36000; // 10 hours

  return generateToken({
    sub: uuid,
    name: name,
    username: name,
    aud: audience,
    scope: 'hytale:server hytale:client',
    iat: now,
    exp: exp,
    iss: `https://sessions.${DOMAIN}`,
    jti: crypto.randomUUID()
  });
}

function handleAuthorizationGrant(req, res, body, uuid, name, headers) {
  console.log('Authorization grant request:', uuid, name, 'body:', JSON.stringify(body));

  // Extract user info from identity token if present in request
  if (body.identityToken) {
    try {
      const parts = body.identityToken.split('.');
      if (parts.length >= 2) {
        const payload = JSON.parse(Buffer.from(parts[1], 'base64url').toString());
        if (payload.sub) uuid = payload.sub;
        if (payload.name) name = payload.name;
        if (payload.username) name = payload.username;
        console.log('Extracted from identity token - uuid:', uuid, 'name:', name);
      }
    } catch (e) {
      console.log('Failed to parse identity token:', e.message);
    }
  }

  // Extract audience from request (server's unique ID)
  const audience = body.aud || body.audience || body.server_id || crypto.randomUUID();

  const authGrant = generateAuthorizationGrant(uuid, name, audience);
  const expiresAt = new Date(Date.now() + 36000 * 1000).toISOString();

  sendJson(res, 200, {
    authorizationGrant: authGrant,
    expiresAt: expiresAt
  });
}

function handleTokenExchange(req, res, body, uuid, name, headers) {
  console.log('Token exchange request:', uuid, name);

  // Extract audience from the authorization grant JWT
  let audience = null;
  if (body.authorizationGrant) {
    try {
      const parts = body.authorizationGrant.split('.');
      if (parts.length >= 2) {
        const payload = JSON.parse(Buffer.from(parts[1], 'base64url').toString());
        audience = payload.aud;
        if (payload.sub) uuid = payload.sub;
        if (payload.name) name = payload.name;
        console.log('Extracted from auth grant - aud:', audience, 'sub:', uuid, 'name:', name);
      }
    } catch (e) {
      console.log('Failed to parse auth grant:', e.message);
    }
  }

  // Get certificate fingerprint from request (for mTLS binding)
  const certFingerprint = body.x509Fingerprint || body.certFingerprint || body.fingerprint;
  console.log('Certificate fingerprint:', certFingerprint);

  const now = Math.floor(Date.now() / 1000);
  const exp = now + 36000; // 10 hours

  // Generate access token with audience and certificate binding
  const tokenPayload = {
    sub: uuid,
    name: name,
    username: name,
    aud: audience,
    entitlements: ['game.base'],
    scope: 'hytale:server hytale:client',
    iat: now,
    exp: exp,
    iss: `https://sessions.${DOMAIN}`,
    jti: crypto.randomUUID()
  };

  // Add certificate confirmation if fingerprint provided (mTLS binding)
  if (certFingerprint) {
    tokenPayload.cnf = {
      'x5t#S256': certFingerprint
    };
  }

  const accessToken = generateToken(tokenPayload);

  const refreshToken = generateSessionToken(uuid);
  const expiresAt = new Date(Date.now() + 36000 * 1000).toISOString();

  sendJson(res, 200, {
    accessToken: accessToken,
    tokenType: 'Bearer',
    expiresIn: 36000,
    refreshToken: refreshToken,
    expiresAt: expiresAt,
    scope: 'hytale:server hytale:client'
  });
}

// Create new game session (used by official launcher and servers)
function handleGameSessionNew(req, res, body, uuid, name) {
  console.log('game-session/new:', uuid, name);

  // Extract UUID from body if provided
  if (body.uuid) uuid = body.uuid;

  const identityToken = generateIdentityToken(uuid, name);
  const sessionToken = generateSessionToken(uuid);
  const expiresAt = new Date(Date.now() + 36000 * 1000).toISOString();

  sendJson(res, 200, {
    sessionToken: sessionToken,
    identityToken: identityToken,
    expiresAt: expiresAt
  });
}

// Refresh existing game session
function handleGameSessionRefresh(req, res, body, uuid, name, headers) {
  console.log('game-session/refresh:', uuid, name);

  // Extract info from existing session token if provided
  if (body.sessionToken) {
    try {
      const token = body.sessionToken;
      const parts = token.split('.');
      if (parts.length >= 2) {
        const payload = JSON.parse(Buffer.from(parts[1], 'base64url').toString());
        if (payload.sub) uuid = payload.sub;
        if (payload.name) name = payload.name;
      }
    } catch (e) {
      console.log('Failed to parse session token:', e.message);
    }
  }

  const identityToken = generateIdentityToken(uuid, name);
  const sessionToken = generateSessionToken(uuid);
  const expiresAt = new Date(Date.now() + 36000 * 1000).toISOString();

  sendJson(res, 200, {
    sessionToken: sessionToken,
    identityToken: identityToken,
    expiresAt: expiresAt
  });
}

function handleGameSessionChild(req, res, body, uuid, name) {
  console.log('game-session/child:', uuid, name);

  const scopes = body.scopes || ['hytale:server'];
  const scopeString = Array.isArray(scopes) ? scopes.join(' ') : scopes;

  const childIdentityToken = generateToken({
    sub: uuid,
    name: name,
    username: name,
    entitlements: ['game.base'],
    scope: scopeString,
    iat: Math.floor(Date.now() / 1000),
    exp: Math.floor(Date.now() / 1000) + 86400,
    iss: `https://sessions.${DOMAIN}`,
    jti: crypto.randomUUID()
  });

  const sessionToken = generateSessionToken(uuid);
  const expiresAt = new Date(Date.now() + 86400 * 1000).toISOString();

  sendJson(res, 200, {
    sessionToken: sessionToken,
    identityToken: childIdentityToken,
    expiresAt: expiresAt
  });
}

function handleSession(req, res, body, uuid, name) {
  sendJson(res, 200, {
    success: true,
    session_id: crypto.randomUUID(),
    identityToken: generateIdentityToken(uuid, name),
    identity_token: generateIdentityToken(uuid, name),
    sessionToken: generateSessionToken(uuid),
    session_token: generateSessionToken(uuid),
    expires_in: 86400,
    token_type: 'Bearer',
    user: { uuid, name, premium: true }
  });
}

function handleAuth(req, res, body, uuid, name) {
  sendJson(res, 200, {
    success: true,
    authenticated: true,
    identity_token: generateIdentityToken(uuid, name),
    session_token: generateSessionToken(uuid),
    token_type: 'Bearer',
    expires_in: 86400,
    user: { uuid, name, premium: true }
  });
}

function handleToken(req, res, body, uuid, name) {
  sendJson(res, 200, {
    access_token: generateIdentityToken(uuid, name),
    identity_token: generateIdentityToken(uuid, name),
    session_token: generateSessionToken(uuid),
    token_type: 'Bearer',
    expires_in: 86400,
    refresh_token: generateSessionToken(uuid)
  });
}

function handleValidate(req, res, body, uuid, name) {
  sendJson(res, 200, {
    valid: true,
    success: true,
    user: { uuid, name, premium: true }
  });
}

function handleRefresh(req, res, body, uuid, name) {
  sendJson(res, 200, {
    success: true,
    identity_token: generateIdentityToken(uuid, name),
    session_token: generateSessionToken(uuid),
    token_type: 'Bearer',
    expires_in: 86400
  });
}

function handleProfile(req, res, body, uuid, name) {
  sendJson(res, 200, {
    success: true,
    uuid, name,
    display_name: name,
    premium: true,
    created_at: '2024-01-01T00:00:00Z',
    settings: { language: 'en', notifications: true },
    stats: { playtime: 0, worlds_created: 0 }
  });
}

function handleSkin(req, res, body, uuid, name) {
  console.log('skin update:', uuid);

  if (!userData[uuid]) {
    userData[uuid] = {};
  }
  userData[uuid].skin = body;
  userData[uuid].lastUpdated = new Date().toISOString();
  saveUserData(userData);

  res.writeHead(204);
  res.end();
}

function handleLauncherData(req, res, body, uuid, name) {
  sendJson(res, 200, {
    EulaAcceptedAt: "2024-01-01T00:00:00Z",
    Owner: uuid,
    Patchlines: {
      PreRelease: { BuildVersion: "1.0.0", Newest: 1 },
      Release: { BuildVersion: "1.0.0", Newest: 1 }
    },
    Profiles: [{
      UUID: uuid,
      Username: name,
      Entitlements: ["game.base"]
    }]
  });
}

function handleGameProfile(req, res, body, uuid, name) {
  const nextNameChange = new Date(Date.now() + 30 * 24 * 60 * 60 * 1000).toISOString();

  let skin = null;
  if (userData[uuid] && userData[uuid].skin) {
    skin = JSON.stringify(userData[uuid].skin);
  }

  sendJson(res, 200, {
    uuid, username: name,
    entitlements: ["game.base"],
    createdAt: "2024-01-01T00:00:00Z",
    nextNameChangeAt: nextNameChange,
    skin: skin
  });
}

// Get all profiles for the authenticated user (used by hytale-server client)
function handleGetProfiles(req, res, body, uuid, name) {
  console.log('get-profiles:', uuid, name);

  sendJson(res, 200, {
    profiles: [{
      uuid: uuid,
      username: name,
      entitlements: ["game.base"]
    }]
  });
}

function handleCosmetics(req, res, body, uuid, name) {
  // Try to load cosmetics from Assets.zip first
  const assetsCosmetics = loadCosmeticsFromAssets();

  if (assetsCosmetics && Object.keys(assetsCosmetics).length > 0) {
    console.log('Returning cosmetics from Assets.zip');
    sendJson(res, 200, assetsCosmetics);
    return;
  }

  // Fallback to basic cosmetics if Assets.zip not available
  console.log('Using fallback cosmetics');
  sendJson(res, 200, {
    bodyCharacteristic: ["Default", "Muscular"],
    cape: ["Cape_Royal_Emissary", "Cape_New_Beginning", "Cape_Forest_Guardian", "Cape_PopStar"],
    earAccessory: [],
    ears: [],
    eyebrows: [],
    eyes: [],
    face: [],
    faceAccessory: [],
    facialHair: [],
    gloves: [],
    haircut: [],
    headAccessory: [],
    mouth: [],
    overpants: [],
    overtop: [],
    pants: [],
    shoes: [],
    skinFeature: [],
    undertop: [],
    underwear: []
  });
}

function sendJson(res, status, data) {
  res.writeHead(status, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify(data));
}

// Start server
console.log('=== Hytale Auth Server ===');
console.log(`Domain: ${DOMAIN}`);
console.log(`Data directory: ${DATA_DIR}`);
console.log(`Assets path: ${ASSETS_PATH}`);

// Pre-load cosmetics on startup
if (fs.existsSync(ASSETS_PATH)) {
  console.log('Assets.zip found, loading cosmetics...');
  loadCosmeticsFromAssets();
} else {
  console.log('Assets.zip not found, using fallback cosmetics');
}

const server = http.createServer(handleRequest);
server.listen(PORT, '0.0.0.0', () => {
  console.log(`Server running on port ${PORT}`);
  console.log(`Endpoints:`);
  console.log(`  - sessions.${DOMAIN}`);
  console.log(`  - account-data.${DOMAIN}`);
  console.log(`  - telemetry.${DOMAIN}`);
});
