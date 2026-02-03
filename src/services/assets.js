const fs = require('fs');
const { execSync } = require('child_process');
const config = require('../config');

// Cache for cosmetics loaded from Assets.zip
let cachedCosmetics = null;
let cachedCosmeticConfigs = null;
let cachedGradientSets = null;
let cachedEyeColors = null;

// Category file mappings
const COSMETIC_CATEGORY_MAP = {
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

const COSMETIC_CONFIG_FILES = {
  'Haircuts.json': 'haircut',
  'Pants.json': 'pants',
  'Overtops.json': 'overtop',
  'Undertops.json': 'undertop',
  'Shoes.json': 'shoes',
  'HeadAccessory.json': 'headAccessory',
  'FaceAccessory.json': 'faceAccessory',
  'EarAccessory.json': 'earAccessory',
  'Eyebrows.json': 'eyebrows',
  'Eyes.json': 'eyes',
  'Faces.json': 'face',
  'FacialHair.json': 'facialHair',
  'Gloves.json': 'gloves',
  'Capes.json': 'cape',
  'Overpants.json': 'overpants',
  'Mouths.json': 'mouth',
  'Ears.json': 'ears',
  'Underwear.json': 'underwear',
  'BodyCharacteristics.json': 'bodyCharacteristic',
};

/**
 * Check if assets file exists
 */
function assetsExist() {
  return fs.existsSync(config.assetsPath);
}

/**
 * Load cosmetics from Assets.zip
 */
function loadCosmeticsFromAssets() {
  if (cachedCosmetics) {
    return cachedCosmetics;
  }

  if (!assetsExist()) {
    console.log('Assets.zip not found at:', config.assetsPath);
    return null;
  }

  console.log('Loading cosmetics from:', config.assetsPath);

  const cosmetics = {};

  for (const [fileName, categoryName] of Object.entries(COSMETIC_CATEGORY_MAP)) {
    const entryPath = `Cosmetics/CharacterCreator/${fileName}`;

    try {
      const content = execSync(`unzip -p "${config.assetsPath}" "${entryPath}"`, {
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

/**
 * Load full cosmetic configs with model paths for avatar rendering
 */
function loadCosmeticConfigs() {
  if (cachedCosmeticConfigs) {
    return cachedCosmeticConfigs;
  }

  if (!assetsExist()) {
    return null;
  }

  const configs = {};

  for (const [fileName, category] of Object.entries(COSMETIC_CONFIG_FILES)) {
    try {
      const content = execSync(`unzip -p "${config.assetsPath}" "Cosmetics/CharacterCreator/${fileName}"`, {
        encoding: 'utf8',
        maxBuffer: 10 * 1024 * 1024
      });
      const items = JSON.parse(content);
      configs[category] = {};
      for (const item of items) {
        if (item && item.Id) {
          configs[category][item.Id] = item;
        }
      }
    } catch (e) {
      // Skip if file doesn't exist
    }
  }

  cachedCosmeticConfigs = configs;
  return configs;
}

/**
 * Load gradient sets for color tinting
 */
function loadGradientSets() {
  if (cachedGradientSets) {
    return cachedGradientSets;
  }

  if (!assetsExist()) {
    return null;
  }

  try {
    const content = execSync(`unzip -p "${config.assetsPath}" "Cosmetics/CharacterCreator/GradientSets.json"`, {
      encoding: 'utf8',
      maxBuffer: 10 * 1024 * 1024
    });
    cachedGradientSets = JSON.parse(content);
    return cachedGradientSets;
  } catch (e) {
    return null;
  }
}

/**
 * Load eye colors
 */
function loadEyeColors() {
  if (cachedEyeColors) {
    return cachedEyeColors;
  }

  if (!assetsExist()) {
    return null;
  }

  try {
    const content = execSync(`unzip -p "${config.assetsPath}" "Cosmetics/CharacterCreator/EyeColors.json"`, {
      encoding: 'utf8',
      maxBuffer: 10 * 1024 * 1024
    });
    const colors = JSON.parse(content);
    cachedEyeColors = {};
    for (const color of colors) {
      if (color.Id) {
        cachedEyeColors[color.Id] = color;
      }
    }
    return cachedEyeColors;
  } catch (e) {
    return null;
  }
}

/**
 * Extract asset from Assets.zip
 */
function extractAsset(assetPath) {
  if (!assetsExist()) {
    return null;
  }

  // Normalize path - try Common/ prefix if not found
  const pathsToTry = [
    assetPath,
    `Common/${assetPath}`,
    assetPath.replace(/^Common\//, '')
  ];

  for (const tryPath of pathsToTry) {
    try {
      const content = execSync(`unzip -p "${config.assetsPath}" "${tryPath}" 2>/dev/null`, {
        maxBuffer: 50 * 1024 * 1024
      });
      return content;
    } catch (e) {
      // Try next path
    }
  }

  return null;
}

/**
 * Resolve skin part to model/texture paths
 */
function resolveSkinPart(category, partValue, configs, gradientSets) {
  if (!partValue || !configs || !configs[category]) {
    return null;
  }

  // Parse "PartId.ColorId" or "PartId.ColorId.Variant" format
  const parts = partValue.split('.');
  const partId = parts[0];
  // Handle empty color (e.g., "ItemId..VariantId") by treating empty string as null
  const colorId = (parts.length > 1 && parts[1]) ? parts[1] : null;
  const variantId = (parts.length > 2 && parts[2]) ? parts[2] : null;

  console.log(`[resolveSkinPart] ${category}: partValue="${partValue}" -> partId="${partId}", colorId="${colorId}", variantId="${variantId}"`);

  const partConfig = configs[category][partId];
  if (!partConfig) {
    console.log(`[resolveSkinPart] ${category}: partConfig not found for "${partId}"`);
    return null;
  }

  const result = {
    id: partId,
    colorId: colorId,
    name: partConfig.Name
  };

  // Handle items with Variants (like capes)
  if (partConfig.Variants) {
    // Use specified variant, or default to Neck_Piece (with shoulder/collar pieces)
    // User can select NoNeck variant to hide shoulder pieces
    const variant = variantId ? partConfig.Variants[variantId] : partConfig.Variants['Neck_Piece'] || Object.values(partConfig.Variants)[0];
    console.log(`[resolveSkinPart] ${category}: variant found:`, variant ? 'yes' : 'no', 'has Textures:', !!variant?.Textures, 'has GreyscaleTexture:', !!variant?.GreyscaleTexture);
    if (variant) {
      result.model = variant.Model;
      result.greyscaleTexture = variant.GreyscaleTexture;

      if (variant.Textures && colorId && variant.Textures[colorId]) {
        result.texture = variant.Textures[colorId].Texture;
        result.baseColor = variant.Textures[colorId].BaseColor;
        console.log(`[resolveSkinPart] ${category}: using Textures[${colorId}] -> texture="${result.texture}"`);
      } else if (variant.GreyscaleTexture && partConfig.GradientSet) {
        result.gradientSet = partConfig.GradientSet;
        if (colorId && gradientSets) {
          const gradientSetConfig = gradientSets.find(g => g.Id === partConfig.GradientSet);
          if (gradientSetConfig && gradientSetConfig.Gradients && gradientSetConfig.Gradients[colorId]) {
            result.gradientTexture = gradientSetConfig.Gradients[colorId].Texture;
            result.baseColor = gradientSetConfig.Gradients[colorId].BaseColor;
          }
        }
      }
    }
    return result;
  }

  // Standard item without variants
  result.model = partConfig.Model;

  if (partConfig.Textures && colorId && partConfig.Textures[colorId]) {
    result.texture = partConfig.Textures[colorId].Texture;
    result.baseColor = partConfig.Textures[colorId].BaseColor;
  } else if (partConfig.GreyscaleTexture) {
    result.greyscaleTexture = partConfig.GreyscaleTexture;
    result.gradientSet = partConfig.GradientSet;

    if (colorId && partConfig.GradientSet && gradientSets) {
      const gradientSetConfig = gradientSets.find(g => g.Id === partConfig.GradientSet);
      if (gradientSetConfig && gradientSetConfig.Gradients && gradientSetConfig.Gradients[colorId]) {
        result.gradientTexture = gradientSetConfig.Gradients[colorId].Texture;
        result.baseColor = gradientSetConfig.Gradients[colorId].BaseColor;
      }
    }
  }

  return result;
}

/**
 * Get fallback cosmetics when Assets.zip is not available
 */
function getFallbackCosmetics() {
  return {
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
  };
}

/**
 * Pre-load cosmetics on startup
 */
function preloadCosmetics() {
  if (assetsExist()) {
    console.log('Assets.zip found, loading cosmetics...');
    loadCosmeticsFromAssets();
  } else {
    console.log('Assets.zip not found, using fallback cosmetics');
  }
}

module.exports = {
  assetsExist,
  loadCosmeticsFromAssets,
  loadCosmeticConfigs,
  loadGradientSets,
  loadEyeColors,
  extractAsset,
  resolveSkinPart,
  getFallbackCosmetics,
  preloadCosmetics,
};
