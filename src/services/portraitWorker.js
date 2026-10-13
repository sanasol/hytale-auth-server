const { parentPort } = require('worker_threads');
const renderer = require('./nativeRenderer');

parentPort.on('message', async ({ id, skin, background }) => {
  try {
    const result = await renderer.renderHeadFromSkinData(skin, background, 128, 128);
    parentPort.postMessage({ id, buffer: result.buffer, timings: result.timings });
  } catch (error) {
    parentPort.postMessage({ id, error: error.message });
  }
});
