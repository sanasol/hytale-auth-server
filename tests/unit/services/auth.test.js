const crypto = require('crypto');

// Mock fs before requiring auth module
jest.mock('fs', () => ({
  existsSync: jest.fn(() => false),
  readFileSync: jest.fn(),
  writeFileSync: jest.fn(),
  mkdirSync: jest.fn(),
}));

const auth = require('../../../src/services/auth');

describe('Auth Service', () => {
  describe('generateToken', () => {
    it('should generate a valid JWT token', () => {
      const payload = {
        sub: 'test-uuid',
        name: 'TestUser',
        iat: Math.floor(Date.now() / 1000),
        exp: Math.floor(Date.now() / 1000) + 3600,
      };

      const token = auth.generateToken(payload);

      expect(token).toBeDefined();
      expect(typeof token).toBe('string');
      expect(token.split('.')).toHaveLength(3);
    });

    it('should include EdDSA algorithm in header', () => {
      const payload = { sub: 'test' };
      const token = auth.generateToken(payload);
      const [header] = token.split('.');
      const decoded = JSON.parse(Buffer.from(header, 'base64url').toString());

      expect(decoded.alg).toBe('EdDSA');
      expect(decoded.typ).toBe('JWT');
    });
  });

  describe('generateIdentityToken', () => {
    it('should generate identity token with correct claims', () => {
      const uuid = 'test-uuid-123';
      const name = 'TestPlayer';

      const token = auth.generateIdentityToken(uuid, name);
      const [, payload] = token.split('.');
      const decoded = JSON.parse(Buffer.from(payload, 'base64url').toString());

      expect(decoded.sub).toBe(uuid);
      expect(decoded.name).toBe(name);
      expect(decoded.username).toBe(name);
      expect(decoded.entitlements).toContain('game.base');
      // Default scope includes all client types (game + editor)
      expect(decoded.scope).toContain('hytale:server');
      expect(decoded.scope).toContain('hytale:client');
      expect(decoded.scope).toContain('hytale:editor');
      // Issuer is dynamic based on request host, defaults to base domain
      expect(decoded.iss).toContain('sanasol.ws');
    });

    it('should include custom entitlements when provided', () => {
      const token = auth.generateIdentityToken('uuid', 'name', null, ['game.base', 'game.premium']);
      const [, payload] = token.split('.');
      const decoded = JSON.parse(Buffer.from(payload, 'base64url').toString());

      expect(decoded.entitlements).toContain('game.base');
      expect(decoded.entitlements).toContain('game.premium');
    });

    it('should include custom scopes when provided', () => {
      const token = auth.generateIdentityToken('uuid', 'name', ['hytale:server', 'hytale:editor']);
      const [, payload] = token.split('.');
      const decoded = JSON.parse(Buffer.from(payload, 'base64url').toString());

      expect(decoded.scope).toBe('hytale:server hytale:editor');
    });

    it('should accept scopes as space-separated string', () => {
      const token = auth.generateIdentityToken('uuid', 'name', 'hytale:server hytale:editor');
      const [, payload] = token.split('.');
      const decoded = JSON.parse(Buffer.from(payload, 'base64url').toString());

      expect(decoded.scope).toBe('hytale:server hytale:editor');
    });
  });

  describe('generateSessionToken', () => {
    it('should generate session token with UUID as subject', () => {
      const uuid = 'session-uuid-456';

      const token = auth.generateSessionToken(uuid);
      const [, payload] = token.split('.');
      const decoded = JSON.parse(Buffer.from(payload, 'base64url').toString());

      expect(decoded.sub).toBe(uuid);
      expect(decoded.scope).toBe('hytale:server');
    });
  });

  describe('generateAuthorizationGrant', () => {
    it('should generate auth grant with audience', () => {
      const uuid = 'player-uuid';
      const name = 'Player';
      const audience = 'server-audience-123';

      const token = auth.generateAuthorizationGrant(uuid, name, audience);
      const [, payload] = token.split('.');
      const decoded = JSON.parse(Buffer.from(payload, 'base64url').toString());

      expect(decoded.sub).toBe(uuid);
      expect(decoded.name).toBe(name);
      expect(decoded.aud).toBe(audience);
    });
  });

  describe('generateAccessToken', () => {
    it('should generate access token with audience', () => {
      const uuid = 'player-uuid';
      const name = 'Player';
      const audience = 'server-123';

      const token = auth.generateAccessToken(uuid, name, audience);
      const [, payload] = token.split('.');
      const decoded = JSON.parse(Buffer.from(payload, 'base64url').toString());

      expect(decoded.sub).toBe(uuid);
      expect(decoded.aud).toBe(audience);
      expect(decoded.entitlements).toContain('game.base');
    });

    it('should include certificate fingerprint when provided', () => {
      const token = auth.generateAccessToken('uuid', 'name', 'aud', 'fingerprint123');
      const [, payload] = token.split('.');
      const decoded = JSON.parse(Buffer.from(payload, 'base64url').toString());

      expect(decoded.cnf).toBeDefined();
      expect(decoded.cnf['x5t#S256']).toBe('fingerprint123');
    });
  });

  describe('normalizeScopes', () => {
    it('should return default scope when scopes is null', () => {
      const result = auth.normalizeScopes(null);
      // Default includes all client types
      expect(result).toContain('hytale:server');
      expect(result).toContain('hytale:client');
      expect(result).toContain('hytale:editor');
    });

    it('should return default scope when scopes is undefined', () => {
      const result = auth.normalizeScopes(undefined);
      expect(result).toContain('hytale:server');
      expect(result).toContain('hytale:client');
      expect(result).toContain('hytale:editor');
    });

    it('should use custom default when provided', () => {
      const result = auth.normalizeScopes(null, 'custom:scope');
      expect(result).toBe('custom:scope');
    });

    it('should join array scopes with spaces', () => {
      const result = auth.normalizeScopes(['hytale:server', 'hytale:editor']);
      expect(result).toBe('hytale:server hytale:editor');
    });

    it('should return string scopes as-is', () => {
      const result = auth.normalizeScopes('hytale:server hytale:client');
      expect(result).toBe('hytale:server hytale:client');
    });
  });

  describe('parseToken', () => {
    it('should parse valid JWT token', () => {
      const token = auth.generateIdentityToken('test-uuid', 'TestName');
      const parsed = auth.parseToken(token);

      expect(parsed).toBeDefined();
      expect(parsed.uuid).toBe('test-uuid');
      expect(parsed.name).toBe('TestName');
    });

    it('should return null for invalid token', () => {
      const result = auth.parseToken('invalid-token');
      expect(result).toBeNull();
    });

    it('should return null for malformed JWT', () => {
      const result = auth.parseToken('a.b.c');
      expect(result).toBeNull();
    });

    it('should include scope in parsed token', () => {
      const token = auth.generateIdentityToken('test-uuid', 'TestName', 'hytale:editor');
      const parsed = auth.parseToken(token);

      expect(parsed.scope).toBe('hytale:editor');
    });
  });

  describe('extractServerAudienceFromHeaders', () => {
    it('should extract audience from bearer token', () => {
      const token = auth.generateAccessToken('uuid', 'name', 'server-aud-123');
      const headers = { authorization: `Bearer ${token}` };

      const audience = auth.extractServerAudienceFromHeaders(headers);
      expect(audience).toBe('server-aud-123');
    });

    it('should return null when no authorization header', () => {
      const result = auth.extractServerAudienceFromHeaders({});
      expect(result).toBeNull();
    });

    it('should return null for invalid token', () => {
      const headers = { authorization: 'Bearer invalid-token' };
      const result = auth.extractServerAudienceFromHeaders(headers);
      expect(result).toBeNull();
    });
  });

  describe('getPublicKeyJwk', () => {
    it('should return public key in JWK format', () => {
      const jwk = auth.getPublicKeyJwk();

      expect(jwk).toBeDefined();
      expect(jwk.kty).toBe('OKP');
      expect(jwk.crv).toBe('Ed25519');
      expect(jwk.x).toBeDefined();
    });
  });
});
