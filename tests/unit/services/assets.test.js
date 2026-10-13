// Mock child_process and fs before requiring the module
jest.mock('child_process');
jest.mock('fs');

describe('Assets Service', () => {
  let assets;
  let execSync;
  let fs;

  beforeEach(() => {
    // Reset modules to clear cached cosmetics/configs
    jest.resetModules();
    jest.clearAllMocks();

    // Re-require mocks after reset
    execSync = require('child_process').execSync;
    fs = require('fs');

    // Default: assets file does not exist
    fs.existsSync.mockReturnValue(false);
  });

  describe('assetsExist', () => {
    it('should return true when assets file exists', () => {
      fs.existsSync.mockReturnValue(true);
      assets = require('../../../src/services/assets');

      expect(assets.assetsExist()).toBe(true);
    });

    it('should return false when assets file does not exist', () => {
      fs.existsSync.mockReturnValue(false);
      assets = require('../../../src/services/assets');

      expect(assets.assetsExist()).toBe(false);
    });
  });

  describe('loadCosmeticsFromAssets', () => {
    it('should return null when assets file does not exist', () => {
      fs.existsSync.mockReturnValue(false);
      assets = require('../../../src/services/assets');

      const result = assets.loadCosmeticsFromAssets();

      expect(result).toBeNull();
    });

    it('should load and parse cosmetics from zip file', () => {
      fs.existsSync.mockReturnValue(true);
      execSync.mockImplementation((cmd) => {
        if (cmd.includes('Haircuts.json')) {
          return JSON.stringify([{ Id: 'Haircut1' }, { Id: 'Haircut2' }]);
        }
        if (cmd.includes('Pants.json')) {
          return JSON.stringify([{ Id: 'Pants1' }]);
        }
        throw new Error('File not found');
      });

      assets = require('../../../src/services/assets');
      const result = assets.loadCosmeticsFromAssets();

      expect(result).toBeDefined();
      expect(result.haircut).toContain('Haircut1');
      expect(result.haircut).toContain('Haircut2');
      expect(result.pants).toContain('Pants1');
    });

    it('should return cached cosmetics on subsequent calls', () => {
      fs.existsSync.mockReturnValue(true);
      execSync.mockReturnValue(JSON.stringify([{ Id: 'Test1' }]));

      assets = require('../../../src/services/assets');
      const result1 = assets.loadCosmeticsFromAssets();
      const result2 = assets.loadCosmeticsFromAssets();

      expect(result1).toBe(result2); // Same reference (cached)
    });

    it('should handle parse errors gracefully', () => {
      fs.existsSync.mockReturnValue(true);
      execSync.mockReturnValue('invalid json');

      assets = require('../../../src/services/assets');
      const result = assets.loadCosmeticsFromAssets();

      // Should return null when all files fail to parse
      expect(result).toBeNull();
    });

    it('should filter out items without Id', () => {
      fs.existsSync.mockReturnValue(true);
      execSync.mockImplementation((cmd) => {
        if (cmd.includes('Haircuts.json')) {
          return JSON.stringify([{ Id: 'Valid' }, { Name: 'NoId' }, null, { Id: 'Valid2' }]);
        }
        throw new Error('File not found');
      });

      assets = require('../../../src/services/assets');
      const result = assets.loadCosmeticsFromAssets();

      expect(result.haircut).toHaveLength(2);
      expect(result.haircut).toContain('Valid');
      expect(result.haircut).toContain('Valid2');
    });
  });

  describe('loadCosmeticConfigs', () => {
    it('should return null when assets file does not exist', () => {
      fs.existsSync.mockReturnValue(false);
      assets = require('../../../src/services/assets');

      const result = assets.loadCosmeticConfigs();

      expect(result).toBeNull();
    });

    it('should load configs with full item data', () => {
      fs.existsSync.mockReturnValue(true);
      execSync.mockImplementation((cmd) => {
        if (cmd.includes('Haircuts.json')) {
          return JSON.stringify([
            { Id: 'Style1', Name: 'Style One', Model: '/models/style1.glb' }
          ]);
        }
        throw new Error('File not found');
      });

      assets = require('../../../src/services/assets');
      const result = assets.loadCosmeticConfigs();

      expect(result).toBeDefined();
      expect(result.haircut).toBeDefined();
      expect(result.haircut.Style1).toBeDefined();
      expect(result.haircut.Style1.Name).toBe('Style One');
      expect(result.haircut.Style1.Model).toBe('/models/style1.glb');
    });

    it('should return cached configs on subsequent calls', () => {
      fs.existsSync.mockReturnValue(true);
      execSync.mockReturnValue(JSON.stringify([{ Id: 'Test1' }]));

      assets = require('../../../src/services/assets');
      const result1 = assets.loadCosmeticConfigs();
      const result2 = assets.loadCosmeticConfigs();

      expect(result1).toBe(result2);
    });
  });

  describe('loadGradientSets', () => {
    it('should return null when assets file does not exist', () => {
      fs.existsSync.mockReturnValue(false);
      assets = require('../../../src/services/assets');

      const result = assets.loadGradientSets();

      expect(result).toBeNull();
    });

    it('should load gradient sets from zip', () => {
      fs.existsSync.mockReturnValue(true);
      const gradientData = [
        { Id: 'HairGradient', Gradients: { Black: { Texture: 'tex1' } } }
      ];
      execSync.mockReturnValue(JSON.stringify(gradientData));

      assets = require('../../../src/services/assets');
      const result = assets.loadGradientSets();

      expect(result).toEqual(gradientData);
    });

    it('should return null on extraction error', () => {
      fs.existsSync.mockReturnValue(true);
      execSync.mockImplementation(() => {
        throw new Error('Extraction failed');
      });

      assets = require('../../../src/services/assets');
      const result = assets.loadGradientSets();

      expect(result).toBeNull();
    });

    it('should return cached gradient sets', () => {
      fs.existsSync.mockReturnValue(true);
      execSync.mockReturnValue(JSON.stringify([{ Id: 'Test' }]));

      assets = require('../../../src/services/assets');
      const result1 = assets.loadGradientSets();
      const result2 = assets.loadGradientSets();

      expect(result1).toBe(result2);
    });
  });

  describe('loadEyeColors', () => {
    it('should return null when assets file does not exist', () => {
      fs.existsSync.mockReturnValue(false);
      assets = require('../../../src/services/assets');

      const result = assets.loadEyeColors();

      expect(result).toBeNull();
    });

    it('should load eye colors as object keyed by Id', () => {
      fs.existsSync.mockReturnValue(true);
      const eyeColorsArray = [
        { Id: 'Blue', BaseColor: '#0000ff' },
        { Id: 'Green', BaseColor: '#00ff00' }
      ];
      execSync.mockReturnValue(JSON.stringify(eyeColorsArray));

      assets = require('../../../src/services/assets');
      const result = assets.loadEyeColors();

      expect(result).toBeDefined();
      expect(result.Blue).toEqual({ Id: 'Blue', BaseColor: '#0000ff' });
      expect(result.Green).toEqual({ Id: 'Green', BaseColor: '#00ff00' });
    });

    it('should skip items without Id', () => {
      fs.existsSync.mockReturnValue(true);
      const eyeColorsArray = [
        { Id: 'Blue', BaseColor: '#0000ff' },
        { BaseColor: '#ff0000' } // No Id
      ];
      execSync.mockReturnValue(JSON.stringify(eyeColorsArray));

      assets = require('../../../src/services/assets');
      const result = assets.loadEyeColors();

      expect(Object.keys(result)).toHaveLength(1);
      expect(result.Blue).toBeDefined();
    });

    it('should return null on extraction error', () => {
      fs.existsSync.mockReturnValue(true);
      execSync.mockImplementation(() => {
        throw new Error('Extraction failed');
      });

      assets = require('../../../src/services/assets');
      const result = assets.loadEyeColors();

      expect(result).toBeNull();
    });
  });

  describe('extractAsset', () => {
    it('should return null when assets file does not exist', () => {
      fs.existsSync.mockReturnValue(false);
      assets = require('../../../src/services/assets');

      const result = assets.extractAsset('some/path.json');

      expect(result).toBeNull();
    });

    it('should extract asset from zip', () => {
      fs.existsSync.mockReturnValue(true);
      const mockContent = Buffer.from('file content');
      execSync.mockReturnValue(mockContent);

      assets = require('../../../src/services/assets');
      const result = assets.extractAsset('Models/character.glb');

      expect(result).toEqual(mockContent);
    });

    it('should try Common/ prefix if first path fails', () => {
      fs.existsSync.mockReturnValue(true);
      execSync.mockImplementation((cmd) => {
        if (cmd.includes('Common/Models')) {
          return Buffer.from('found with prefix');
        }
        throw new Error('Not found');
      });

      assets = require('../../../src/services/assets');
      const result = assets.extractAsset('Models/test.glb');

      expect(result.toString()).toBe('found with prefix');
    });

    it('should try without Common/ prefix if path has it', () => {
      fs.existsSync.mockReturnValue(true);
      execSync.mockImplementation((cmd) => {
        if (cmd.includes('"Models/test.glb"')) {
          return Buffer.from('found without prefix');
        }
        throw new Error('Not found');
      });

      assets = require('../../../src/services/assets');
      const result = assets.extractAsset('Common/Models/test.glb');

      expect(result.toString()).toBe('found without prefix');
    });

    it('should return null if all paths fail', () => {
      fs.existsSync.mockReturnValue(true);
      execSync.mockImplementation(() => {
        throw new Error('Not found');
      });

      assets = require('../../../src/services/assets');
      const result = assets.extractAsset('nonexistent/file.txt');

      expect(result).toBeNull();
    });
  });

  describe('resolveSkinPart', () => {
    beforeEach(() => {
      fs.existsSync.mockReturnValue(false);
      assets = require('../../../src/services/assets');
    });

    it('should return null for null/undefined partValue', () => {
      expect(assets.resolveSkinPart('haircut', null, {}, [])).toBeNull();
      expect(assets.resolveSkinPart('haircut', undefined, {}, [])).toBeNull();
    });

    it('should return null for null configs', () => {
      expect(assets.resolveSkinPart('haircut', 'Style1', null, [])).toBeNull();
    });

    it('should return null for missing category in configs', () => {
      const configs = { pants: {} };
      expect(assets.resolveSkinPart('haircut', 'Style1', configs, [])).toBeNull();
    });

    it('should return null for unknown part ID', () => {
      const configs = { haircut: { Style1: { Id: 'Style1' } } };
      expect(assets.resolveSkinPart('haircut', 'Unknown', configs, [])).toBeNull();
    });

    it('should parse PartId.ColorId format', () => {
      const configs = {
        haircut: {
          Style1: {
            Id: 'Style1',
            Name: 'Style One',
            Model: '/models/style1.glb',
            Textures: {
              Black: { Texture: 'tex_black.png', BaseColor: '#000000' }
            }
          }
        }
      };

      const result = assets.resolveSkinPart('haircut', 'Style1.Black', configs, []);

      expect(result.id).toBe('Style1');
      expect(result.colorId).toBe('Black');
      expect(result.texture).toBe('tex_black.png');
      expect(result.baseColor).toBe('#000000');
    });

    it('resolves renamed saved cosmetics through fallback aliases', () => {
      const configs = { haircut: { SpikyMohawk: {
        Id: 'SpikyMohawk', FallbackPartIds: ['SpikyMohawl'], Model: 'm', GreyscaleTexture: 'g'
      } } };
      expect(assets.resolveSkinPart('haircut', 'SpikyMohawl.Black', configs, []).id).toBe('SpikyMohawk');
    });

    it('ignores placeholder texture entries and chooses a valid default gradient', () => {
      const configs = { pants: { Dungarees: {
        Id: 'Dungarees', GradientSet: 'Jean_Generic', Textures: { ShortDungareesOneShoulder: {} },
        Variants: { ShortDungareesRegular: { Model: 'm', GreyscaleTexture: 'g' } }
      } } };
      const result = assets.resolveSkinPart('pants', 'Dungarees', configs,
        [{ Id: 'Jean_Generic', Gradients: { Blue: { Texture: 'blue.png', BaseColor: ['#123456'] } } }]);
      expect(result.greyscaleTexture).toBe('g');
      expect(result.gradientTexture).toBe('blue.png');
      expect(result.texture).toBeUndefined();
    });

    it('returns body textures and accessory hiding metadata from the selected config', () => {
      const configs = { bodyCharacteristic: { Goblin: {
        Id: 'Goblin', Model: 'player', GreyscaleTexture: 'goblin.png', GradientSet: 'Skin'
      } }, headAccessory: { Hoodie: { Id: 'Hoodie', HidesSkinProperties: ['Ears', 'EarAccessory'] } } };
      expect(assets.resolveSkinPart('bodyCharacteristic', 'Goblin.22', configs, []).greyscaleTexture).toBe('goblin.png');
      expect(assets.resolveSkinPart('headAccessory', 'Hoodie', configs, []).hidesSkinProperties).toEqual(['Ears', 'EarAccessory']);
    });

    it('should handle items with Variants (like capes)', () => {
      const configs = {
        cape: {
          Cape1: {
            Id: 'Cape1',
            Name: 'Cape One',
            Variants: {
              Neck_Piece: {
                Model: '/models/cape_neck.glb',
                GreyscaleTexture: 'grey.png'
              },
              Back: {
                Model: '/models/cape_back.glb'
              }
            },
            GradientSet: 'CapeGradient'
          }
        }
      };

      const result = assets.resolveSkinPart('cape', 'Cape1', configs, []);

      expect(result.id).toBe('Cape1');
      expect(result.model).toBe('/models/cape_neck.glb'); // Default to Neck_Piece
    });

    it('should use specific variant when provided', () => {
      const configs = {
        cape: {
          Cape1: {
            Id: 'Cape1',
            Variants: {
              Neck_Piece: { Model: '/models/neck.glb' },
              Back: { Model: '/models/back.glb' }
            }
          }
        }
      };

      const result = assets.resolveSkinPart('cape', 'Cape1.Red.Back', configs, []);

      expect(result.model).toBe('/models/back.glb');
    });

    it('should resolve gradient from gradientSets', () => {
      const configs = {
        haircut: {
          Style1: {
            Id: 'Style1',
            GreyscaleTexture: 'grey.png',
            GradientSet: 'HairGradient'
          }
        }
      };
      const gradientSets = [
        {
          Id: 'HairGradient',
          Gradients: {
            Black: { Texture: 'gradient_black.png', BaseColor: '#111111' }
          }
        }
      ];

      const result = assets.resolveSkinPart('haircut', 'Style1.Black', configs, gradientSets);

      expect(result.gradientTexture).toBe('gradient_black.png');
      expect(result.baseColor).toBe('#111111');
    });

    it('should handle variant with color textures', () => {
      const configs = {
        cape: {
          Cape1: {
            Id: 'Cape1',
            Variants: {
              Neck_Piece: {
                Model: '/models/cape.glb',
                Textures: {
                  Gold: { Texture: 'cape_gold.png', BaseColor: '#ffd700' }
                }
              }
            }
          }
        }
      };

      const result = assets.resolveSkinPart('cape', 'Cape1.Gold', configs, []);

      expect(result.texture).toBe('cape_gold.png');
      expect(result.baseColor).toBe('#ffd700');
    });

    it('should handle variant with gradient set', () => {
      const configs = {
        cape: {
          Cape1: {
            Id: 'Cape1',
            GradientSet: 'CapeGradient',
            Variants: {
              Neck_Piece: {
                Model: '/models/cape.glb',
                GreyscaleTexture: 'grey.png'
              }
            }
          }
        }
      };
      const gradientSets = [
        {
          Id: 'CapeGradient',
          Gradients: {
            Red: { Texture: 'grad_red.png', BaseColor: '#ff0000' }
          }
        }
      ];

      const result = assets.resolveSkinPart('cape', 'Cape1.Red', configs, gradientSets);

      expect(result.gradientTexture).toBe('grad_red.png');
      expect(result.baseColor).toBe('#ff0000');
    });

    it('should use first variant as fallback when Neck_Piece not found', () => {
      const configs = {
        cape: {
          Cape1: {
            Id: 'Cape1',
            Variants: {
              Back: { Model: '/models/back.glb' },
              Front: { Model: '/models/front.glb' }
            }
          }
        }
      };

      const result = assets.resolveSkinPart('cape', 'Cape1', configs, []);

      // Should use first variant (Back)
      expect(result.model).toBeDefined();
    });
  });

  describe('getFallbackCosmetics', () => {
    beforeEach(() => {
      fs.existsSync.mockReturnValue(false);
      assets = require('../../../src/services/assets');
    });

    it('should return fallback cosmetics structure', () => {
      const result = assets.getFallbackCosmetics();

      expect(result).toBeDefined();
      expect(result.bodyCharacteristic).toContain('Default');
      expect(result.bodyCharacteristic).toContain('Muscular');
      expect(result.cape).toContain('Cape_Royal_Emissary');
    });

    it('should have all expected categories', () => {
      const result = assets.getFallbackCosmetics();

      const expectedCategories = [
        'bodyCharacteristic', 'cape', 'earAccessory', 'ears', 'eyebrows',
        'eyes', 'face', 'faceAccessory', 'facialHair', 'gloves', 'haircut',
        'headAccessory', 'mouth', 'overpants', 'overtop', 'pants', 'shoes',
        'skinFeature', 'undertop', 'underwear'
      ];

      for (const cat of expectedCategories) {
        expect(result).toHaveProperty(cat);
        expect(Array.isArray(result[cat])).toBe(true);
      }
    });
  });

  describe('preloadCosmetics', () => {
    it('should call loadCosmeticsFromAssets when assets exist', () => {
      fs.existsSync.mockReturnValue(true);
      execSync.mockReturnValue(JSON.stringify([{ Id: 'Test' }]));

      assets = require('../../../src/services/assets');
      assets.preloadCosmetics();

      expect(execSync).toHaveBeenCalled();
    });

    it('should not throw when assets do not exist', () => {
      fs.existsSync.mockReturnValue(false);
      assets = require('../../../src/services/assets');

      expect(() => assets.preloadCosmetics()).not.toThrow();
    });
  });
});
