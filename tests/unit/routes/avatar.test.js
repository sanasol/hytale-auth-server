// Mock dependencies
jest.mock('../../../src/services/storage');
jest.mock('../../../src/services/assets');
jest.mock('fs');

const storage = require('../../../src/services/storage');
const assets = require('../../../src/services/assets');
const fs = require('fs');
const avatarRoutes = require('../../../src/routes/avatar');

describe('Avatar Routes', () => {
  let mockRes;
  let mockReq;

  beforeEach(() => {
    jest.clearAllMocks();
    assets.applyCosmeticCompatibility.mockImplementation(jest.requireActual('../../../src/services/assets').applyCosmeticCompatibility);
    mockRes = {
      writeHead: jest.fn(),
      setHeader: jest.fn(),
      end: jest.fn(),
    };
    mockReq = {
      method: 'GET',
      url: '/avatar/test-uuid',
      headers: { host: 'localhost:3000' },
    };

    // Default mocks
    storage.getUserData.mockResolvedValue({});
    assets.loadCosmeticConfigs.mockReturnValue({});
    assets.loadGradientSets.mockReturnValue([]);
    assets.loadEyeColors.mockReturnValue({});
    assets.resolveSkinPart.mockReturnValue(null);
    assets.applyEyeColor.mockImplementation(jest.requireActual('../../../src/services/assets').applyEyeColor);
    fs.existsSync.mockReturnValue(false);
    fs.readFileSync.mockReturnValue(Buffer.from(''));
    fs.writeFileSync.mockReturnValue();
    fs.mkdirSync.mockReturnValue();
    fs.statSync.mockReturnValue({ mtimeMs: Date.now(), mtime: new Date() });
    fs.readdirSync.mockReturnValue([]);
    fs.unlinkSync.mockReturnValue();
  });

  describe('handleAvatarRoutes', () => {
    it('should return 400 for missing UUID', async () => {
      await avatarRoutes.handleAvatarRoutes(mockReq, mockRes, '/avatar/', {});

      expect(mockRes.writeHead).toHaveBeenCalledWith(400, expect.any(Object));
    });

    it('should serve avatar viewer for base path', async () => {
      await avatarRoutes.handleAvatarRoutes(mockReq, mockRes, '/avatar/test-uuid', {});

      expect(mockRes.writeHead).toHaveBeenCalledWith(200, expect.any(Object));
      const html = mockRes.end.mock.calls[0][0];
      expect(html).toContain('<!DOCTYPE html>');
    });
  });

  describe('handleAvatarModel', () => {
    it('should return 404 when user has no skin data', async () => {
      storage.getUserData.mockResolvedValue({});

      await avatarRoutes.handleAvatarModel(mockReq, mockRes, 'test-uuid');

      expect(mockRes.writeHead).toHaveBeenCalledWith(404, expect.any(Object));
      const response = JSON.parse(mockRes.end.mock.calls[0][0]);
      expect(response.error).toBe('User skin not found');
    });

    it('should return model data for UUID with skin', async () => {
      storage.getUserData.mockResolvedValue({
        skin: {
          bodyCharacteristic: 'Regular.01',
          haircut: 'Style1.Black',
        },
      });
      assets.loadCosmeticConfigs.mockReturnValue({
        haircut: { Style1: { Id: 'Style1', Model: '/models/haircut.glb' } }
      });
      assets.resolveSkinPart.mockReturnValue({ id: 'Style1', model: '/models/haircut.glb' });

      await avatarRoutes.handleAvatarModel(mockReq, mockRes, 'test-uuid');

      expect(mockRes.writeHead).toHaveBeenCalledWith(200, expect.any(Object));
      const response = JSON.parse(mockRes.end.mock.calls[0][0]);
      expect(response.uuid).toBe('test-uuid');
      expect(response.skinTone).toBe('01');
      expect(response.bodyType).toBe('Regular');
      expect(response.parts).toBeDefined();
      expect(response.raw).toBeDefined();
    });

    it('exposes the configured body and hides accessory-covered categories without losing raw skin', async () => {
      const raw = { bodyCharacteristic: 'Goblin.22', ears: 'Elf.22', headAccessory: 'Hoodie.Black' };
      storage.getUserData.mockResolvedValue({ skin: raw });
      assets.resolveSkinPart.mockImplementation((category) => {
        if (category === 'bodyCharacteristic') return { id: 'Goblin', greyscaleTexture: 'goblin.png' };
        if (category === 'headAccessory') return { id: 'Hoodie', hidesSkinProperties: ['Ears'] };
        return { id: 'Elf' };
      });
      await avatarRoutes.handleAvatarModel(mockReq, mockRes, 'test-uuid');
      const result = JSON.parse(mockRes.end.mock.calls[0][0]);
      expect(result.body.greyscaleTexture).toBe('goblin.png');
      expect(result.parts.ears).toBeUndefined();
      expect(result.raw.ears).toBe('Elf.22');
    });

    it('should return default body type and skin tone when not set', async () => {
      storage.getUserData.mockResolvedValue({
        skin: {}  // Empty skin object
      });

      await avatarRoutes.handleAvatarModel(mockReq, mockRes, 'test-uuid');

      expect(mockRes.writeHead).toHaveBeenCalledWith(200, expect.any(Object));
      const response = JSON.parse(mockRes.end.mock.calls[0][0]);
      expect(response.skinTone).toBe('01');
      expect(response.bodyType).toBe('Regular');
    });

    it('should parse bodyCharacteristic with skin tone', async () => {
      storage.getUserData.mockResolvedValue({
        skin: {
          bodyCharacteristic: 'Muscular.22'
        }
      });

      await avatarRoutes.handleAvatarModel(mockReq, mockRes, 'test-uuid');

      const response = JSON.parse(mockRes.end.mock.calls[0][0]);
      expect(response.skinTone).toBe('22');
      expect(response.bodyType).toBe('Muscular');
    });

    it('should pad single digit skin tones', async () => {
      storage.getUserData.mockResolvedValue({
        skin: {
          bodyCharacteristic: 'Regular.5'
        }
      });

      await avatarRoutes.handleAvatarModel(mockReq, mockRes, 'test-uuid');

      const response = JSON.parse(mockRes.end.mock.calls[0][0]);
      expect(response.skinTone).toBe('05');
    });

    it('should prefer the current bodyCharacteristic tone over legacy skinTone', async () => {
      storage.getUserData.mockResolvedValue({
        skin: {
          bodyCharacteristic: 'Regular.01',
          skinTone: '15'
        }
      });

      await avatarRoutes.handleAvatarModel(mockReq, mockRes, 'test-uuid');

      const response = JSON.parse(mockRes.end.mock.calls[0][0]);
      expect(response.skinTone).toBe('01');
    });
  });

  describe('handleAvatarPreview', () => {
    it('should return 405 for non-POST requests', async () => {
      mockReq.method = 'GET';
      const customSkin = { bodyCharacteristic: 'Regular.01' };

      await avatarRoutes.handleAvatarPreview(mockReq, mockRes, 'preview-uuid', customSkin);

      expect(mockRes.writeHead).toHaveBeenCalledWith(405, expect.any(Object));
      const response = JSON.parse(mockRes.end.mock.calls[0][0]);
      expect(response.error).toBe('Method not allowed, use POST');
    });

    it('should preview custom skin data with POST', async () => {
      mockReq.method = 'POST';
      const customSkin = {
        bodyCharacteristic: 'Muscular.10',
        haircut: 'Style1.Red',
      };
      assets.resolveSkinPart.mockReturnValue({ id: 'Style1', colorId: 'Red' });

      await avatarRoutes.handleAvatarPreview(mockReq, mockRes, 'preview-uuid', customSkin);

      expect(mockRes.writeHead).toHaveBeenCalledWith(200, expect.any(Object));
      const response = JSON.parse(mockRes.end.mock.calls[0][0]);
      expect(response.uuid).toBe('preview-uuid');
      expect(response.skinTone).toBe('10');
      expect(response.bodyType).toBe('Muscular');
    });
  });

  describe('handleCustomizerRoute', () => {
    it('should return customizer HTML', () => {
      avatarRoutes.handleCustomizerRoute(mockReq, mockRes, '/customizer/test-uuid');

      expect(mockRes.writeHead).toHaveBeenCalledWith(200, expect.any(Object));
      const html = mockRes.end.mock.calls[0][0];
      expect(html).toContain('html');
    });

    it('should use "preview" as default UUID', () => {
      avatarRoutes.handleCustomizerRoute(mockReq, mockRes, '/customizer');

      expect(mockRes.writeHead).toHaveBeenCalledWith(200, expect.any(Object));
    });

    it('should load from views directory if exists', () => {
      fs.existsSync.mockReturnValue(true);
      fs.readFileSync.mockReturnValue('<html>Custom Customizer {{UUID}}</html>');

      avatarRoutes.handleCustomizerRoute(mockReq, mockRes, '/customizer/my-uuid');

      const html = mockRes.end.mock.calls[0][0];
      expect(html).toContain('my-uuid');
    });
  });

  describe('handleTestHeadPage', () => {
    it('should return test head page HTML with static test UUID', async () => {
      await avatarRoutes.handleTestHeadPage(mockReq, mockRes);

      expect(mockRes.writeHead).toHaveBeenCalledWith(200, expect.any(Object));
      const html = mockRes.end.mock.calls[0][0];
      expect(html).toContain('Head Embed Test');
      // Uses static test UUID
      expect(html).toContain('03fbfdef-9c4a-4eef-bd10-63fa96427133');
      expect(html).toContain('TestPlayer');
    });
  });

  describe('invalidateHeadCache', () => {
    it('should delete cached head images for UUID', () => {
      fs.readdirSync.mockReturnValue([
        'uuid1_black.png',
        'uuid1_white.png',
        'other_black.png',
      ]);

      avatarRoutes.invalidateHeadCache('uuid1');

      expect(fs.unlinkSync).toHaveBeenCalledTimes(2);
    });

    it('should handle errors gracefully', () => {
      fs.readdirSync.mockImplementation(() => {
        throw new Error('Directory not found');
      });

      // Should not throw
      expect(() => avatarRoutes.invalidateHeadCache('uuid')).not.toThrow();
    });
  });

  describe('handleAvatarRoutes routing', () => {
    it('should route to model endpoint', async () => {
      storage.getUserData.mockResolvedValue({ skin: { bodyCharacteristic: 'Regular.01' } });

      await avatarRoutes.handleAvatarRoutes(mockReq, mockRes, '/avatar/test-uuid/model', {});

      expect(mockRes.writeHead).toHaveBeenCalledWith(200, expect.any(Object));
      const response = JSON.parse(mockRes.end.mock.calls[0][0]);
      expect(response.uuid).toBe('test-uuid');
    });

    it('should route to preview endpoint with POST', async () => {
      mockReq.method = 'POST';
      const body = { bodyCharacteristic: 'Regular.01' };

      await avatarRoutes.handleAvatarRoutes(mockReq, mockRes, '/avatar/test-uuid/preview', body);

      expect(mockRes.writeHead).toHaveBeenCalledWith(200, expect.any(Object));
      const response = JSON.parse(mockRes.end.mock.calls[0][0]);
      expect(response.uuid).toBe('test-uuid');
    });

    it('should route to head endpoint', async () => {
      mockReq.url = '/avatar/test-uuid/head';

      await avatarRoutes.handleAvatarRoutes(mockReq, mockRes, '/avatar/test-uuid/head', {});

      // Returns HTML for head embed rendering
      expect(mockRes.writeHead).toHaveBeenCalledWith(200, expect.any(Object));
    });
  });

  describe('handleAvatarModel with skin parts', () => {
    it('should resolve skin parts from configs', async () => {
      storage.getUserData.mockResolvedValue({
        skin: {
          bodyCharacteristic: 'Regular.01',
          haircut: 'Style1.Black',
          pants: 'Jeans.Blue',
        },
      });

      assets.loadCosmeticConfigs.mockReturnValue({
        haircut: { Style1: { Id: 'Style1' } },
        pants: { Jeans: { Id: 'Jeans' } }
      });

      assets.resolveSkinPart.mockImplementation((cat, data) => {
        if (cat === 'haircut') return { id: 'Style1', model: '/models/haircut1.glb' };
        if (cat === 'pants') return { id: 'Jeans', model: '/models/pants1.glb' };
        return null;
      });

      await avatarRoutes.handleAvatarModel(mockReq, mockRes, 'test-uuid');

      const response = JSON.parse(mockRes.end.mock.calls[0][0]);
      expect(response.parts.haircut).toBeDefined();
      expect(response.parts.pants).toBeDefined();
    });

    it('should handle eye color separately', async () => {
      storage.getUserData.mockResolvedValue({
        skin: {
          bodyCharacteristic: 'Regular.01',
          eyes: 'EyeStyle1.Default',
          eyeColor: 'Blue'
        },
      });

      assets.loadCosmeticConfigs.mockReturnValue({
        eyes: { EyeStyle1: { Id: 'EyeStyle1' } }
      });

      assets.loadEyeColors.mockReturnValue({
        Blue: { Id: 'Blue', BaseColor: '#0000ff' }
      });

      assets.resolveSkinPart.mockImplementation((cat) => {
        if (cat === 'eyes') return { id: 'EyeStyle1', gradientSet: 'Eyes_Gradient' };
        return null;
      });

      await avatarRoutes.handleAvatarModel(mockReq, mockRes, 'test-uuid');

      const response = JSON.parse(mockRes.end.mock.calls[0][0]);
      expect(response.parts.eyes).toBeDefined();
      expect(response.parts.eyes.baseColor).toBe('#0000ff');
    });
  });

  describe('serveAvatarViewer', () => {
    it('should load from views directory if template exists', async () => {
      fs.existsSync.mockImplementation((path) => path.includes('avatar-viewer.html'));
      fs.readFileSync.mockReturnValue('<html>{{UUID}} viewer</html>');

      await avatarRoutes.handleAvatarRoutes(mockReq, mockRes, '/avatar/custom-uuid', {});

      const html = mockRes.end.mock.calls[0][0];
      expect(html).toContain('custom-uuid');
    });

    it('should use inline viewer when template does not exist', async () => {
      fs.existsSync.mockReturnValue(false);

      await avatarRoutes.handleAvatarRoutes(mockReq, mockRes, '/avatar/custom-uuid', {});

      const html = mockRes.end.mock.calls[0][0];
      expect(html).toContain('Hytale Avatar Viewer');
      expect(html).toContain('custom-uuid');
    });
  });
});
