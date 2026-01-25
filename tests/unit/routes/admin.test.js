// Mock dependencies
jest.mock('../../../src/services/storage');
jest.mock('fs');

const storage = require('../../../src/services/storage');
const fs = require('fs');
const adminRoutes = require('../../../src/routes/admin');

describe('Admin Routes', () => {
  let mockRes;

  beforeEach(() => {
    jest.clearAllMocks();
    mockRes = {
      writeHead: jest.fn(),
      end: jest.fn(),
    };

    // Default mocks
    storage.createAdminToken.mockResolvedValue(true);
    storage.verifyAdminToken.mockResolvedValue(false);
    storage.isRedisConnected.mockReturnValue(true);
    storage.getAllActiveSessions.mockResolvedValue({ sessions: [], servers: [] });
    storage.getKeyCounts.mockResolvedValue({
      sessions: 0,
      authGrants: 0,
      users: 0,
      servers: 0,
      activePlayers: 0
    });
    storage.getPaginatedServers.mockResolvedValue({
      servers: [],
      pagination: { page: 1, limit: 10, totalServers: 0, totalPages: 0, hasNext: false, hasPrev: false }
    });
    storage.setServerName.mockResolvedValue(true);
    fs.existsSync.mockReturnValue(false);
    fs.readdirSync.mockReturnValue([]);
  });

  describe('handleAdminLogin', () => {
    it('should return token on successful login', async () => {
      const req = {};
      const body = { password: 'changeme' };

      await adminRoutes.handleAdminLogin(req, mockRes, body);

      expect(storage.createAdminToken).toHaveBeenCalled();
      expect(mockRes.writeHead).toHaveBeenCalledWith(200, expect.any(Object));
      const response = JSON.parse(mockRes.end.mock.calls[0][0]);
      expect(response.token).toBeDefined();
    });

    it('should return 401 on invalid password', async () => {
      const req = {};
      const body = { password: 'wrong-password' };

      await adminRoutes.handleAdminLogin(req, mockRes, body);

      expect(storage.createAdminToken).not.toHaveBeenCalled();
      expect(mockRes.writeHead).toHaveBeenCalledWith(401, expect.any(Object));
    });

    it('should return 400 when password is missing', async () => {
      const req = {};
      const body = {};

      await adminRoutes.handleAdminLogin(req, mockRes, body);

      expect(mockRes.writeHead).toHaveBeenCalledWith(400, expect.any(Object));
    });

    it('should return 500 when token creation fails', async () => {
      storage.createAdminToken.mockResolvedValue(false);
      const req = {};
      const body = { password: 'changeme' };

      await adminRoutes.handleAdminLogin(req, mockRes, body);

      expect(mockRes.writeHead).toHaveBeenCalledWith(500, expect.any(Object));
    });
  });

  describe('handleAdminVerify', () => {
    it('should return valid:true for valid token', async () => {
      storage.verifyAdminToken.mockResolvedValue(true);
      const req = {};

      await adminRoutes.handleAdminVerify(req, mockRes, 'valid-token');

      expect(mockRes.writeHead).toHaveBeenCalledWith(200, expect.any(Object));
      const response = JSON.parse(mockRes.end.mock.calls[0][0]);
      expect(response.valid).toBe(true);
    });

    it('should return 401 for invalid token', async () => {
      const req = {};

      await adminRoutes.handleAdminVerify(req, mockRes, 'invalid-token');

      expect(mockRes.writeHead).toHaveBeenCalledWith(401, expect.any(Object));
    });

    it('should return 401 for null token', async () => {
      const req = {};

      await adminRoutes.handleAdminVerify(req, mockRes, null);

      expect(mockRes.writeHead).toHaveBeenCalledWith(401, expect.any(Object));
    });
  });

  describe('handleActiveSessions', () => {
    it('should return active sessions from storage', async () => {
      storage.getAllActiveSessions.mockResolvedValue({
        sessions: [{ uuid: 'uuid1', username: 'Player1' }],
        servers: [{ audience: 'server1', playerCount: 1 }],
      });

      const req = {};

      await adminRoutes.handleActiveSessions(req, mockRes);

      expect(mockRes.writeHead).toHaveBeenCalledWith(200, expect.any(Object));
      const response = JSON.parse(mockRes.end.mock.calls[0][0]);
      expect(response.sessions).toBeDefined();
      expect(response.servers).toBeDefined();
    });
  });

  describe('handleAdminStats', () => {
    it('should return stats from getKeyCounts', async () => {
      storage.getKeyCounts.mockResolvedValue({
        sessions: 10,
        authGrants: 5,
        users: 100,
        servers: 3,
        activePlayers: 15
      });

      const req = {};

      await adminRoutes.handleAdminStats(req, mockRes);

      expect(storage.getKeyCounts).toHaveBeenCalled();
      const response = JSON.parse(mockRes.end.mock.calls[0][0]);
      expect(response.activeSessions).toBe(10);
      expect(response.activeServers).toBe(3);
      expect(response.activePlayers).toBe(15);
      expect(response.keys.users).toBe(100);
      expect(response.redis.connected).toBe(true);
      expect(response.uptime).toBeDefined();
      expect(response.memory).toBeDefined();
    });

    it('should handle redis disconnected state', async () => {
      storage.isRedisConnected.mockReturnValue(false);

      const req = {};

      await adminRoutes.handleAdminStats(req, mockRes);

      const response = JSON.parse(mockRes.end.mock.calls[0][0]);
      expect(response.redis.connected).toBe(false);
    });
  });

  describe('handleAdminServers', () => {
    it('should return paginated servers from storage', async () => {
      storage.getPaginatedServers.mockResolvedValue({
        servers: [
          { audience: 's1', name: 'Server 1', playerCount: 5, players: [] },
          { audience: 's2', name: 'Server 2', playerCount: 3, players: [] },
        ],
        pagination: {
          page: 1,
          limit: 10,
          totalServers: 3,
          totalPages: 1,
          hasNext: false,
          hasPrev: false
        }
      });

      const req = {};
      const url = new URL('http://test.com/admin/servers?page=1&limit=10');

      await adminRoutes.handleAdminServers(req, mockRes, url);

      expect(storage.getPaginatedServers).toHaveBeenCalledWith(1, 10);
      const response = JSON.parse(mockRes.end.mock.calls[0][0]);
      expect(response.servers).toHaveLength(2);
      expect(response.pagination.page).toBe(1);
      expect(response.pagination.totalServers).toBe(3);
    });

    it('should use default page and limit', async () => {
      const req = {};
      const url = new URL('http://test.com/admin/servers');

      await adminRoutes.handleAdminServers(req, mockRes, url);

      expect(storage.getPaginatedServers).toHaveBeenCalledWith(1, 10);
    });

    it('should cap limit at 50', async () => {
      const req = {};
      const url = new URL('http://test.com/admin/servers?limit=100');

      await adminRoutes.handleAdminServers(req, mockRes, url);

      expect(storage.getPaginatedServers).toHaveBeenCalledWith(1, 50);
    });

    it('should handle empty servers', async () => {
      storage.getPaginatedServers.mockResolvedValue({
        servers: [],
        pagination: { page: 1, limit: 10, totalServers: 0, totalPages: 0, hasNext: false, hasPrev: false }
      });

      const req = {};
      const url = new URL('http://test.com/admin/servers');

      await adminRoutes.handleAdminServers(req, mockRes, url);

      const response = JSON.parse(mockRes.end.mock.calls[0][0]);
      expect(response.servers).toHaveLength(0);
      expect(response.pagination.totalPages).toBe(0);
    });
  });

  describe('handlePrerenderQueue', () => {
    it('should return counts only (optimized, no UUIDs)', async () => {
      fs.existsSync.mockReturnValue(true);
      fs.readdirSync.mockReturnValue(['uuid1_black.png', 'uuid2_white.png']);
      storage.getKeyCounts.mockResolvedValue({
        sessions: 5,
        authGrants: 3,
        users: 50,
        servers: 2,
        activePlayers: 10
      });

      const req = {};

      await adminRoutes.handlePrerenderQueue(req, mockRes);

      const response = JSON.parse(mockRes.end.mock.calls[0][0]);
      expect(response.total).toBe(10);
      expect(response.totalPlayers).toBe(10);
      expect(response.cached).toBe(2);
      expect(response.totalCachedFiles).toBe(2);
      expect(response.uncached).toBe(8);
      // Optimized version does NOT return uuids array
      expect(response.uuids).toBeUndefined();
    });

    it('should handle missing cache directory', async () => {
      fs.existsSync.mockReturnValue(false);
      storage.getKeyCounts.mockResolvedValue({
        sessions: 0,
        authGrants: 0,
        users: 0,
        servers: 0,
        activePlayers: 5
      });

      const req = {};

      await adminRoutes.handlePrerenderQueue(req, mockRes);

      const response = JSON.parse(mockRes.end.mock.calls[0][0]);
      expect(response.totalCachedFiles).toBe(0);
      expect(response.cached).toBe(0);
      expect(response.uncached).toBe(5);
    });

    it('should handle redis not connected', async () => {
      storage.isRedisConnected.mockReturnValue(false);

      const req = {};

      await adminRoutes.handlePrerenderQueue(req, mockRes);

      const response = JSON.parse(mockRes.end.mock.calls[0][0]);
      expect(response.totalPlayers).toBe(0);
      expect(response.error).toBe('Redis not connected');
    });

    it('should only count .png files in cache', async () => {
      fs.existsSync.mockReturnValue(true);
      fs.readdirSync.mockReturnValue(['uuid1_black.png', 'uuid2_white.png', 'readme.txt', '.gitkeep']);
      storage.getKeyCounts.mockResolvedValue({
        sessions: 0,
        authGrants: 0,
        users: 0,
        servers: 0,
        activePlayers: 5
      });

      const req = {};

      await adminRoutes.handlePrerenderQueue(req, mockRes);

      const response = JSON.parse(mockRes.end.mock.calls[0][0]);
      expect(response.totalCachedFiles).toBe(2);
      expect(response.cached).toBe(2);
    });
  });

  describe('handleAdminDashboard', () => {
    it('should return HTML dashboard', () => {
      const req = {};

      adminRoutes.handleAdminDashboard(req, mockRes);

      expect(mockRes.writeHead).toHaveBeenCalledWith(200, {
        'Content-Type': 'text/html',
      });
      const html = mockRes.end.mock.calls[0][0];
      expect(html).toContain('<!DOCTYPE html>');
      expect(html).toContain('Admin');
    });

    it('should contain login form elements', () => {
      const req = {};

      adminRoutes.handleAdminDashboard(req, mockRes);

      const html = mockRes.end.mock.calls[0][0];
      expect(html).toContain('loginForm');
      expect(html).toContain('loginPassword');
      expect(html).toContain('loginOverlay');
    });

    it('should contain stats cards', () => {
      const req = {};

      adminRoutes.handleAdminDashboard(req, mockRes);

      const html = mockRes.end.mock.calls[0][0];
      expect(html).toContain('playerCount');
      expect(html).toContain('serverCount');
      expect(html).toContain('prerenderQueue');
      expect(html).toContain('prerenderCached');
    });

  });
});
