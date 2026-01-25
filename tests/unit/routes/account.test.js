// Mock dependencies
jest.mock('../../../src/services/storage');
jest.mock('../../../src/services/assets');
jest.mock('../../../src/services/auth');

const storage = require('../../../src/services/storage');
const assets = require('../../../src/services/assets');
const auth = require('../../../src/services/auth');
const accountRoutes = require('../../../src/routes/account');

describe('Account Routes', () => {
  let mockRes;

  beforeEach(() => {
    jest.clearAllMocks();
    mockRes = {
      writeHead: jest.fn(),
      end: jest.fn(),
      setHeader: jest.fn(),
    };

    // Default mocks
    storage.getUserData.mockResolvedValue({});
    storage.saveUserData.mockResolvedValue();
    storage.getUsername.mockResolvedValue(null);
    storage.getCachedUsername.mockReturnValue(null);
    storage.getPlayersOnServer.mockResolvedValue([]);
    storage.findPlayerOnServer.mockResolvedValue([]);
    storage.getAllActiveSessions.mockResolvedValue({ sessions: [], servers: [] });
    auth.extractServerAudienceFromHeaders.mockReturnValue(null);
    assets.loadCosmeticsFromAssets.mockReturnValue({});
    assets.getFallbackCosmetics.mockReturnValue({ default: true });
  });

  describe('handleProfile', () => {
    it('should return user profile', () => {
      const req = {};
      const body = {};

      accountRoutes.handleProfile(req, mockRes, body, 'test-uuid', 'TestPlayer');

      expect(mockRes.writeHead).toHaveBeenCalledWith(200, expect.any(Object));
      const response = JSON.parse(mockRes.end.mock.calls[0][0]);
      expect(response.uuid).toBe('test-uuid');
      expect(response.name).toBe('TestPlayer');
      expect(response.premium).toBe(true);
    });
  });

  describe('handleProfileLookupByUuid', () => {
    it('should return profile by UUID', async () => {
      storage.getUsername.mockResolvedValue('FoundPlayer');
      const req = {};
      const headers = {};

      await accountRoutes.handleProfileLookupByUuid(req, mockRes, 'lookup-uuid', headers);

      expect(mockRes.writeHead).toHaveBeenCalledWith(200, expect.any(Object));
      const response = JSON.parse(mockRes.end.mock.calls[0][0]);
      expect(response.uuid).toBe('lookup-uuid');
      expect(response.username).toBe('FoundPlayer');
    });

    it('should return generic name when UUID not found', async () => {
      const req = {};
      const headers = {};

      await accountRoutes.handleProfileLookupByUuid(req, mockRes, 'unknown-uuid', headers);

      const response = JSON.parse(mockRes.end.mock.calls[0][0]);
      expect(response.uuid).toBe('unknown-uuid');
      expect(response.username).toContain('Player_');
    });

    it('should check server context first', async () => {
      auth.extractServerAudienceFromHeaders.mockReturnValue('server-123');
      storage.getPlayersOnServer.mockResolvedValue([
        { uuid: 'lookup-uuid', username: 'ActivePlayer' },
      ]);

      const req = {};
      const headers = { authorization: 'Bearer token' };

      await accountRoutes.handleProfileLookupByUuid(req, mockRes, 'lookup-uuid', headers);

      const response = JSON.parse(mockRes.end.mock.calls[0][0]);
      expect(response.username).toBe('ActivePlayer');
    });
  });

  describe('handleProfileLookupByUsername', () => {
    it('should return profile by username from server', async () => {
      auth.extractServerAudienceFromHeaders.mockReturnValue('server-123');
      storage.findPlayerOnServer.mockResolvedValue([
        { uuid: 'found-uuid', username: 'TargetPlayer' },
      ]);

      const req = {};
      const headers = {};

      await accountRoutes.handleProfileLookupByUsername(req, mockRes, 'TargetPlayer', headers);

      expect(mockRes.writeHead).toHaveBeenCalledWith(200, expect.any(Object));
      const response = JSON.parse(mockRes.end.mock.calls[0][0]);
      expect(response.uuid).toBe('found-uuid');
    });

    it('should return 404 when username not found', async () => {
      const req = {};
      const headers = {};

      await accountRoutes.handleProfileLookupByUsername(req, mockRes, 'UnknownPlayer', headers);

      expect(mockRes.writeHead).toHaveBeenCalledWith(404, expect.any(Object));
    });

    it('should search globally when not found on server', async () => {
      auth.extractServerAudienceFromHeaders.mockReturnValue('server-123');
      storage.findPlayerOnServer.mockResolvedValue([]);
      storage.getAllActiveSessions.mockResolvedValue({
        sessions: [{ uuid: 'global-uuid', username: 'TargetPlayer' }],
        servers: [],
      });

      const req = {};
      const headers = {};

      await accountRoutes.handleProfileLookupByUsername(req, mockRes, 'TargetPlayer', headers);

      const response = JSON.parse(mockRes.end.mock.calls[0][0]);
      expect(response.uuid).toBe('global-uuid');
    });
  });

  describe('handleGameProfile', () => {
    it('should return game profile with skin data', async () => {
      storage.getUserData.mockResolvedValue({
        skin: { haircut: 'style1', color: '#fff' },
      });

      const req = {};
      const body = {};

      await accountRoutes.handleGameProfile(req, mockRes, body, 'test-uuid', 'TestPlayer');

      expect(mockRes.writeHead).toHaveBeenCalledWith(200, expect.any(Object));
      const response = JSON.parse(mockRes.end.mock.calls[0][0]);
      expect(response.uuid).toBe('test-uuid');
      expect(response.entitlements).toContain('game.base');
      expect(response.skin).toBeDefined();
    });
  });

  describe('handleSkin', () => {
    it('should update user skin data', async () => {
      const req = {};
      const body = { haircut: 'new-style', skinColor: '#f5c6a5' };
      const invalidateCache = jest.fn();

      await accountRoutes.handleSkin(req, mockRes, body, 'uuid', 'name', invalidateCache);

      expect(storage.getUserData).toHaveBeenCalledWith('uuid');
      expect(storage.saveUserData).toHaveBeenCalled();
      expect(invalidateCache).toHaveBeenCalledWith('uuid');
      expect(mockRes.writeHead).toHaveBeenCalledWith(204);
    });
  });

  describe('handleLauncherData', () => {
    it('should return launcher data', () => {
      const req = {};
      const body = {};

      accountRoutes.handleLauncherData(req, mockRes, body, 'uuid', 'TestPlayer');

      const response = JSON.parse(mockRes.end.mock.calls[0][0]);
      expect(response.Owner).toBe('uuid');
      expect(response.Profiles).toHaveLength(1);
      expect(response.Profiles[0].Username).toBe('TestPlayer');
    });
  });

  describe('handleGetProfiles', () => {
    it('should return profiles array', () => {
      const req = {};
      const body = {};

      accountRoutes.handleGetProfiles(req, mockRes, body, 'uuid', 'Player');

      const response = JSON.parse(mockRes.end.mock.calls[0][0]);
      expect(response.profiles).toHaveLength(1);
      expect(response.profiles[0].uuid).toBe('uuid');
    });
  });

  describe('handleCosmetics', () => {
    it('should return cosmetics from assets', () => {
      assets.loadCosmeticsFromAssets.mockReturnValue({
        haircut: { style1: {} },
        pants: { jeans: {} },
      });

      const req = {};
      const body = {};

      accountRoutes.handleCosmetics(req, mockRes, body, 'uuid', 'name');

      const response = JSON.parse(mockRes.end.mock.calls[0][0]);
      expect(response.haircut).toBeDefined();
    });

    it('should fallback to default cosmetics', () => {
      assets.loadCosmeticsFromAssets.mockReturnValue({});

      const req = {};
      const body = {};

      accountRoutes.handleCosmetics(req, mockRes, body, 'uuid', 'name');

      expect(assets.getFallbackCosmetics).toHaveBeenCalled();
    });
  });
});
