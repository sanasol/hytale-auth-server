const assets = require('../services/assets');
const storage = require('../services/storage');
const config = require('../config');
const { sendJson, sendBinary } = require('../utils/response');

/**
 * Category to gradient set mapping
 * This maps which gradient set each category uses by default
 */
const CATEGORY_GRADIENT_MAP = {
  'haircut': 'Hair',
  'eyebrows': 'Hair',
  'facialHair': 'Hair',
  'eyes': 'Eyes_Gradient',
  'skinTone': 'Skin',
  'pants': null,  // Uses item-specific Textures or various gradient sets
  'overtop': null,
  'undertop': null,
  'shoes': null,
  'gloves': null,
  'cape': null,
  'headAccessory': null,
  'faceAccessory': null,
  'earAccessory': null,
  'face': null,
  'mouth': null,
  'ears': null,
  'underwear': null,
  'overpants': null,
  'bodyCharacteristic': null  // No colors
};

/**
 * Build a color hex map from gradient sets
 */
function buildColorHexMap(gradientSets) {
  const colorHexMap = {};

  if (!gradientSets) return colorHexMap;

  for (const gradientSet of gradientSets) {
    if (!gradientSet.Gradients) continue;

    for (const [colorId, colorData] of Object.entries(gradientSet.Gradients)) {
      if (colorData.BaseColor && colorData.BaseColor.length > 0) {
        // Use first base color if array
        const baseColor = Array.isArray(colorData.BaseColor)
          ? colorData.BaseColor[0]
          : colorData.BaseColor;
        colorHexMap[colorId] = baseColor;
      }
    }
  }

  return colorHexMap;
}

/**
 * Get colors for an item based on its gradient set or textures
 */
function getItemColors(item, gradientSets) {
  const colors = [];

  // If item has explicit Textures with color variants
  if (item.Textures && typeof item.Textures === 'object') {
    return Object.keys(item.Textures);
  }

  // If item has Variants (like capes), get colors from the first variant's Textures
  if (item.Variants) {
    const firstVariant = Object.values(item.Variants)[0];
    if (firstVariant) {
      // Check variant's Textures (for non-greyscale items like skeleton cape)
      if (firstVariant.Textures && typeof firstVariant.Textures === 'object') {
        return Object.keys(firstVariant.Textures);
      }
      // Check variant's GradientSet (for greyscale items like king cape)
      if (firstVariant.GreyscaleTexture && item.GradientSet && gradientSets) {
        const gradientSet = gradientSets.find(g => g.Id === item.GradientSet);
        if (gradientSet && gradientSet.Gradients) {
          return Object.keys(gradientSet.Gradients);
        }
      }
    }
  }

  // If item has a gradient set, get colors from that set
  if (item.GradientSet && gradientSets) {
    const gradientSet = gradientSets.find(g => g.Id === item.GradientSet);
    if (gradientSet && gradientSet.Gradients) {
      return Object.keys(gradientSet.Gradients);
    }
  }

  return colors;
}

/**
 * Check if a cosmetic item is complete (has required model and texture files)
 * Returns false if the item is likely broken or unavailable in-game
 */
function isItemComplete(item) {
  // Items without Variants - check Model and either GreyscaleTexture or Textures
  if (!item.Variants) {
    if (!item.Model) return false;
    if (!item.GreyscaleTexture && (!item.Textures || Object.keys(item.Textures).length === 0)) {
      return false;
    }
    return true;
  }

  // Items with Variants - check each variant has Model and texture
  // Need at least one complete variant
  const variants = Object.values(item.Variants);
  if (variants.length === 0) return false;

  for (const variant of variants) {
    if (!variant.Model) continue;
    if (variant.GreyscaleTexture || (variant.Textures && Object.keys(variant.Textures).length > 0)) {
      return true; // At least one complete variant found
    }
  }
  return false;
}

/**
 * Get thumbnail path for an item
 * Returns path that works with /asset/ route (tries Common/ prefix)
 */
function getItemThumbnail(item) {
  let texturePath = null;

  // Try greyscale texture first (most items have this)
  if (item.GreyscaleTexture) {
    texturePath = item.GreyscaleTexture;
  }
  // Try first texture from Textures object
  else if (item.Textures && typeof item.Textures === 'object') {
    const firstTexture = Object.values(item.Textures)[0];
    if (firstTexture && firstTexture.Texture) {
      texturePath = firstTexture.Texture;
    }
  }
  // Try Variants (for capes etc)
  else if (item.Variants) {
    const firstVariant = Object.values(item.Variants)[0];
    if (firstVariant) {
      if (firstVariant.GreyscaleTexture) {
        texturePath = firstVariant.GreyscaleTexture;
      } else if (firstVariant.Textures) {
        const firstTex = Object.values(firstVariant.Textures)[0];
        if (firstTex && firstTex.Texture) {
          texturePath = firstTex.Texture;
        }
      }
    }
  }

  // Normalize path - ensure it starts with a known prefix
  if (texturePath) {
    // Paths in configs are relative to Common/ or Cosmetics/ inside Assets.zip
    // The extractAsset function tries multiple prefixes, so we just return as-is
    return texturePath;
  }

  return null;
}

/**
 * Cosmetics list API endpoint - returns properly structured data for customizer
 */
function handleCosmeticsList(req, res) {
  const configs = assets.loadCosmeticConfigs();
  const gradientSets = assets.loadGradientSets();

  if (!configs) {
    sendJson(res, 200, {});
    return;
  }

  // Build global color hex map from all gradient sets
  const colorHexMap = buildColorHexMap(gradientSets);

  // Transform configs into customizer-friendly format
  const result = {};

  for (const [category, items] of Object.entries(configs)) {
    const categoryItems = [];

    for (const [itemId, item] of Object.entries(items)) {
      // Filter out incomplete items that are missing required files
      if (!isItemComplete(item)) {
        continue;
      }

      const colors = getItemColors(item, gradientSets);
      const thumbnail = getItemThumbnail(item);

      // Check if item has variants (like capes with Neck_Piece/NoNeck)
      let variants = null;
      if (item.Variants) {
        variants = Object.keys(item.Variants).map(variantId => ({
          id: variantId,
          name: item.Variants[variantId].NameKey || variantId
        }));
      }

      const itemData = {
        id: itemId,
        name: item.Name || itemId,
        thumbnail: thumbnail,
        colors: colors,
        gradientSet: item.GradientSet || null,
        model: item.Model || null,
        variants: variants
      };

      // Add HeadAccessory-specific fields (for haircut interaction logic)
      if (category === 'headAccessory' && item.HeadAccessoryType) {
        itemData.headAccessoryType = item.HeadAccessoryType; // Simple, HalfCovering, FullyCovering
      }

      // Add Haircut-specific fields (for fallback logic)
      if (category === 'haircut') {
        if (item.HairType) {
          itemData.hairType = item.HairType; // Short, Medium, Long
        }
        if (item.RequiresGenericHaircut !== undefined) {
          itemData.requiresGenericHaircut = item.RequiresGenericHaircut;
        }
      }

      // Add Entitlements (for filtering available items)
      if (item.Entitlements && item.Entitlements.length > 0) {
        itemData.entitlements = item.Entitlements;
      }

      // Add IsDefaultAsset (for preselection in customizer)
      if (item.IsDefaultAsset) {
        itemData.isDefault = true;
      }

      // Add DisableCharacterPartCategory (for item interaction logic)
      if (item.DisableCharacterPartCategory) {
        itemData.disablesCategory = item.DisableCharacterPartCategory.toLowerCase();
      }

      // Add VariantLocalizationKey (for variant label lookup)
      if (item.VariantLocalizationKey) {
        itemData.variantLocalizationKey = item.VariantLocalizationKey;
      }

      categoryItems.push(itemData);
    }

    // Sort items alphabetically by ID for consistent ordering
    categoryItems.sort((a, b) => a.id.localeCompare(b.id));

    result[category] = categoryItems;
  }

  // NOTE: skinTone is NOT a separate category - it's the COLOR of bodyCharacteristic
  // bodyCharacteristic items (Default, Muscular) use GradientSet: "Skin" which provides
  // all 47 skin tone colors. Users select body type, then pick skin color.

  // Also return gradient sets info and color map for the UI
  sendJson(res, 200, {
    items: result,
    colorHexMap: colorHexMap,
    gradientSets: (gradientSets || []).map(gs => ({
      id: gs.Id,
      colors: gs.Gradients ? Object.keys(gs.Gradients) : []
    }))
  });
}

/**
 * Single cosmetic item data API (for 3D thumbnail rendering)
 */
function handleCosmeticItem(req, res, urlPath) {
  const parts = urlPath.replace('/cosmetics/item/', '').split('/');
  if (parts.length < 2) {
    sendJson(res, 400, { error: 'Category and item ID required' });
    return;
  }

  const category = parts[0];
  const itemId = parts[1];

  const configs = assets.loadCosmeticConfigs();
  const gradientSets = assets.loadGradientSets();

  if (!configs || !configs[category] || !configs[category][itemId]) {
    sendJson(res, 404, { error: 'Item not found' });
    return;
  }

  const item = configs[category][itemId];
  sendJson(res, 200, {
    category,
    item,
    gradientSets: gradientSets || []
  });
}

/**
 * Static assets route (for js, css, etc from assets folder)
 */
function handleStaticAssets(req, res, urlPath) {
  const fs = require('fs');
  const path = require('path');

  const relativePath = urlPath.replace('/assets/', '');
  const filePath = path.join(__dirname, '../../assets', relativePath);

  if (!fs.existsSync(filePath)) {
    res.writeHead(404);
    res.end('Not found');
    return;
  }

  const ext = path.extname(filePath).toLowerCase();
  const contentTypes = {
    '.js': 'application/javascript',
    '.css': 'text/css',
    '.html': 'text/html',
    '.json': 'application/json',
    '.png': 'image/png',
    '.jpg': 'image/jpeg',
    '.jpeg': 'image/jpeg',
    '.gif': 'image/gif',
    '.svg': 'image/svg+xml',
  };

  const contentType = contentTypes[ext] || 'application/octet-stream';
  const content = fs.readFileSync(filePath);

  sendBinary(res, 200, content, contentType, {
    'Cache-Control': 'public, max-age=3600'
  });
}

/**
 * Handle asset extraction route (extract from Assets.zip)
 */
function handleAssetRoute(req, res, urlPath) {
  const assetPath = urlPath.replace('/asset/', '');

  if (!assetPath) {
    sendJson(res, 400, { error: 'Asset path required' });
    return;
  }

  const content = assets.extractAsset(assetPath);

  if (!content) {
    res.writeHead(404);
    res.end('Asset not found');
    return;
  }

  // Determine content type
  let contentType = 'application/octet-stream';
  if (assetPath.endsWith('.json') || assetPath.endsWith('.blockymodel') || assetPath.endsWith('.blockyanim')) {
    contentType = 'application/json';
  } else if (assetPath.endsWith('.png')) {
    contentType = 'image/png';
  } else if (assetPath.endsWith('.jpg') || assetPath.endsWith('.jpeg')) {
    contentType = 'image/jpeg';
  }

  sendBinary(res, 200, content, contentType, {
    'Cache-Control': 'public, max-age=86400'
  });
}

/**
 * Download route - redirect to CDN or serve from local
 * Used for HytaleServer.jar, Assets.zip and other downloadable files
 *
 * Behavior:
 * 1. Check if CDN link is configured for this file
 * 2. If yes, redirect (302) to CDN and record metrics
 * 3. If no, serve from local downloads directory
 */
async function handleDownload(req, res, urlPath) {
  const fs = require('fs');
  const path = require('path');

  // Extract filename from path (e.g., /download/HytaleServer.jar -> HytaleServer.jar)
  const filename = urlPath.replace('/download/', '');

  // Security: prevent path traversal
  if (filename.includes('..') || filename.includes('/') || filename.includes('\\')) {
    sendJson(res, 400, { error: 'Invalid filename' });
    return;
  }

  // Check if we have a CDN link configured
  const downloadLink = await storage.getDownloadLink(filename);

  if (downloadLink.isExternal && downloadLink.url) {
    // Record the download metric
    storage.recordDownload(filename, downloadLink.url);

    // Redirect to CDN
    console.log(`Download redirect: ${filename} -> ${downloadLink.url.substring(0, 50)}...`);
    res.writeHead(302, {
      'Location': downloadLink.url,
      'Cache-Control': 'no-cache'
    });
    res.end();
    return;
  }

  // Fallback: serve from local downloads directory
  const filePath = path.join(config.downloadsDir, filename);

  if (!fs.existsSync(filePath)) {
    sendJson(res, 404, { error: 'File not found and no CDN link configured' });
    return;
  }

  try {
    const stat = fs.statSync(filePath);
    const content = fs.readFileSync(filePath);

    // Record download from local
    storage.recordDownload(filename, 'local');

    // Determine content type
    let contentType = 'application/octet-stream';
    if (filename.endsWith('.jar')) {
      contentType = 'application/java-archive';
    } else if (filename.endsWith('.zip')) {
      contentType = 'application/zip';
    }

    res.writeHead(200, {
      'Content-Type': contentType,
      'Content-Length': stat.size,
      'Content-Disposition': `attachment; filename="${filename}"`,
      'Cache-Control': 'public, max-age=3600'
    });
    res.end(content);
  } catch (e) {
    console.error('Download error:', e.message);
    sendJson(res, 500, { error: 'Failed to serve file' });
  }
}

module.exports = {
  handleCosmeticsList,
  handleCosmeticItem,
  handleStaticAssets,
  handleAssetRoute,
  handleDownload,
};
