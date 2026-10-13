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
  'SkinFeatures.json': 'skinFeature',
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
  const rawPartId = parts[0];
  // Handle empty color (e.g., "ItemId..VariantId") by treating empty string as null
  const colorId = (parts.length > 1 && parts[1]) ? parts[1] : null;
  const variantId = (parts.length > 2 && parts[2]) ? parts[2] : null;

  console.log(`[resolveSkinPart] ${category}: partValue="${partValue}" -> partId="${rawPartId}", colorId="${colorId}", variantId="${variantId}"`);

  // 1. Try exact match
  let partConfig = configs[category][rawPartId];
  let partId = rawPartId;

  // 2. Fuzzy match (Case-insensitive) if exact match fails
  // Fixes "naked character" issue when mock IDs use different casing than asset files
  if (!partConfig) {
    const lowerRawId = rawPartId.toLowerCase();
    const potentialMatch = Object.keys(configs[category]).find(key =>
      key.toLowerCase() === lowerRawId
    );

    if (potentialMatch) {
      partConfig = configs[category][potentialMatch];
      partId = potentialMatch;
      console.log(`[resolveSkinPart] Fuzzy matched "${rawPartId}" -> "${potentialMatch}"`);
    }
  }

  // Renamed 0.7 cosmetics retain old identifiers as FallbackPartIds.
  // Resolve those aliases for saved skins without rewriting the user's raw data.
  if (!partConfig) {
    const alias = Object.values(configs[category]).find(item =>
      item.FallbackPartIds?.some(id => id.toLowerCase() === rawPartId.toLowerCase()));
    if (alias) {
      partConfig = alias;
      partId = alias.Id;
    }
  }

  if (!partConfig) {
    console.log(`[resolveSkinPart] ${category}: partConfig not found for "${rawPartId}"`);
    return null;
  }

  // Variant definitions override the item while retaining shared metadata.
  const variantKey = partConfig.Variants
    ? (variantId && partConfig.Variants[variantId] ? variantId : Object.keys(partConfig.Variants)[0])
    : null;
  const effective = variantKey ? { ...partConfig, ...partConfig.Variants[variantKey] } : partConfig;
  const result = {
    id: partId,
    colorId,
    name: partConfig.Name,
    model: effective.Model,
    variantId: variantKey,
    hidesSkinProperties: effective.HidesSkinProperties || [],
    headAccessoryType: effective.HeadAccessoryType,
    hairType: effective.HairType,
    requiresGenericHaircut: effective.RequiresGenericHaircut,
    priority: effective.Priority
  };

  // A missing color must still select an actual texture/gradient, rather than
  // render an untextured solid polygon. Empty texture entries are placeholders.
  const textures = effective.Textures || {};
  const selectedTexture = colorId && textures[colorId]?.Texture ? colorId : null;
  const defaultTexture = Object.keys(textures).find(key => textures[key]?.Texture);
  if (selectedTexture || (!effective.GreyscaleTexture && defaultTexture)) {
    const textureKey = selectedTexture || defaultTexture;
    result.texture = textures[textureKey].Texture;
    result.baseColor = textures[textureKey].BaseColor;
    result.colorId = textureKey;
  } else if (effective.GreyscaleTexture) {
    result.greyscaleTexture = effective.GreyscaleTexture;
    result.gradientSet = effective.GradientSet;
    const gradients = gradientSets?.find(g => g.Id === effective.GradientSet)?.Gradients || {};
    const gradientKey = colorId && gradients[colorId] ? colorId : Object.keys(gradients)[0];
    if (gradientKey) {
      result.gradientTexture = gradients[gradientKey].Texture;
      result.baseColor = gradients[gradientKey].BaseColor;
      result.colorId = gradientKey;
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

// Hidden cosmetics stay in raw skin so removing a covering accessory restores them.
function applyCosmeticCompatibility(parts, configs, gradientSets) {
  const hat = parts.headAccessory;
  const hair = parts.haircut;
  if (hat?.headAccessoryType === 'HalfCovering' && hair?.requiresGenericHaircut && hair.hairType) {
    const fallback = resolveSkinPart('haircut', `Generic${hair.hairType}.${hair.colorId || ''}`, configs, gradientSets);
    if (fallback) parts.haircut = fallback;
  }
  const categoryKeys = new Map(Object.keys(parts).map(key => [key.toLowerCase(), key]));
  const hidden = new Set();
  for (const part of Object.values(parts)) {
    for (const property of part.hidesSkinProperties || []) {
      const key = categoryKeys.get(property.toLowerCase());
      if (key && key !== 'bodyCharacteristic') hidden.add(key);
    }
  }
  for (const key of hidden) delete parts[key];
}

function applyEyeColor(parts, eyeColor, eyeColors, gradientSets) {
  if (!eyeColor || !parts.eyes || !eyeColors?.[eyeColor]) return;
  parts.eyes.baseColor = eyeColors[eyeColor].BaseColor;
  const gradient = gradientSets?.find(set => set.Id === parts.eyes.gradientSet)?.Gradients?.[eyeColor];
  if (gradient) parts.eyes.gradientTexture = gradient.Texture;
  parts.eyes.colorId = eyeColor;
}

module.exports = {
  assetsExist,
  loadCosmeticsFromAssets,
  loadCosmeticConfigs,
  loadGradientSets,
  loadEyeColors,
  extractAsset,
  resolveSkinPart,
  applyCosmeticCompatibility,
  applyEyeColor,
  getFallbackCosmetics,
  preloadCosmetics,
};
