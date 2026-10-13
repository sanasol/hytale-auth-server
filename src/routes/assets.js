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
  const colors = new Set();
  const definitions = item.Variants
    ? Object.values(item.Variants).map(variant => ({ ...item, ...variant, Variants: null }))
    : [item];
  for (const definition of definitions) {
    for (const [color, texture] of Object.entries(definition.Textures || {})) {
      if (texture.Texture) colors.add(color);
    }
    if (definition.GreyscaleTexture && definition.GradientSet) {
      const gradients = gradientSets?.find(set => set.Id === definition.GradientSet)?.Gradients || {};
      for (const color of Object.keys(gradients)) colors.add(color);
    }
  }
  return [...colors];
}

/**
 * Check if a cosmetic item is complete (has required model and texture files)
 * Returns false if the item is likely broken or unavailable in-game
 */
function isItemComplete(item) {
  const definitions = item.Variants
    ? Object.values(item.Variants).map(variant => ({ ...item, ...variant, Variants: null }))
    : [item];
  return definitions.some(definition => definition.Model && (
    definition.GreyscaleTexture || Object.values(definition.Textures || {}).some(texture => texture.Texture)
  ));
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
          name: item.Variants[variantId].NameKey || variantId,
          colors: getItemColors({ ...item, ...item.Variants[variantId], Variants: null }, gradientSets)
        }));
      }

      const itemData = {
        id: itemId,
        name: item.Name || itemId,
        thumbnail: thumbnail,
        colors: colors,
        gradientSet: item.GradientSet || null,
        model: item.Model || null,
        variants: variants,
        colorHexMap: buildColorHexMap((gradientSets || []).filter(set => set.Id === item.GradientSet))
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

  // Fallback: serve from local downloads directory. Assets.zip is the same file
  // used by cosmetics/avatar APIs, so keep it tied to ASSETS_PATH.
  const filePath = filename === 'Assets.zip'
    ? config.assetsPath
    : path.join(config.downloadsDir, filename);

  if (!fs.existsSync(filePath)) {
    sendJson(res, 404, { error: 'File not found and no CDN link configured' });
    return;
  }

  try {
    const stat = fs.statSync(filePath);

    // Record download from local
    storage.recordDownload(filename, 'local');

    // Determine content type
    let contentType = 'application/octet-stream';
    if (filename.endsWith('.jar')) {
      contentType = 'application/java-archive';
    } else if (filename.endsWith('.zip')) {
      contentType = 'application/zip';
    }

    const baseHeaders = {
      'Content-Type': contentType,
      'Content-Disposition': `attachment; filename="${filename}"`,
      'Cache-Control': 'public, max-age=300',
      'Accept-Ranges': 'bytes'
    };

    const range = req.headers.range;
    if (range) {
      const match = /^bytes=(\d*)-(\d*)$/.exec(range);
      if (!match) {
        res.writeHead(416, {
          ...baseHeaders,
          'Content-Range': `bytes */${stat.size}`
        });
        res.end();
        return;
      }

      let start = match[1] ? parseInt(match[1], 10) : 0;
      let end = match[2] ? parseInt(match[2], 10) : stat.size - 1;
      if (!match[1] && match[2]) {
        const suffixLength = parseInt(match[2], 10);
        start = Math.max(stat.size - suffixLength, 0);
        end = stat.size - 1;
      }

      if (start > end || start >= stat.size) {
        res.writeHead(416, {
          ...baseHeaders,
          'Content-Range': `bytes */${stat.size}`
        });
        res.end();
        return;
      }

      end = Math.min(end, stat.size - 1);
      res.writeHead(206, {
        ...baseHeaders,
        'Content-Length': end - start + 1,
        'Content-Range': `bytes ${start}-${end}/${stat.size}`
      });
      if (req.method === 'HEAD') {
        res.end();
        return;
      }
      fs.createReadStream(filePath, { start, end }).pipe(res);
      return;
    }

    res.writeHead(200, {
      ...baseHeaders,
      'Content-Length': stat.size
    });
    if (req.method === 'HEAD') {
      res.end();
      return;
    }
    fs.createReadStream(filePath).pipe(res);
  } catch (e) {
    console.error('Download error:', e.message);
    sendJson(res, 500, { error: 'Failed to serve file' });
  }
}

/**
 * Patch redirect route - redirects to configured CDN for patch files
 * Used by F2P launcher to fetch manifest.json and .pwr patch files
 * Admin can change the CDN base URL instantly via settings
 */
async function handlePatchRedirect(req, res, urlPath) {
  // Extract path after /patches/ (e.g., manifest.json, windows/amd64/release/0_to_11.pwr)
  const patchPath = urlPath.replace('/patches/', '');

  // Security: prevent path traversal
  if (patchPath.includes('..')) {
    res.writeHead(400);
    res.end('Invalid path');
    return;
  }

  const baseUrl = await storage.getPatchesCdnBaseUrl();
  const redirectUrl = `${baseUrl.replace(/\/+$/, '')}/${patchPath}`;

  // Track download metrics for patch files
  const filename = patchPath.split('/').pop() || patchPath;
  storage.recordDownload(`patch:${filename}`, redirectUrl);

  console.log(`Patch redirect: ${patchPath} -> ${redirectUrl.substring(0, 80)}...`);
  res.writeHead(302, {
    'Location': redirectUrl,
    'Cache-Control': 'no-cache'
  });
  res.end();
}

module.exports = {
  handleCosmeticsList,
  handleCosmeticItem,
  handleStaticAssets,
  handleAssetRoute,
  handleDownload,
  handlePatchRedirect,
};
