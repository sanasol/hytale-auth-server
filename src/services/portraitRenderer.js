const { Worker } = require('worker_threads');
const cluster = require('cluster');
const path = require('path');

// One serial GL worker keeps unzip, image decoding and drawing off the auth loop.
class PortraitQueue {
  constructor({ workerPath = path.join(__dirname, 'portraitWorker.js'), timeoutMs = 30000, maxPending = 16 } = {}) {
    this.workerPath = workerPath;
    this.timeoutMs = timeoutMs;
    this.maxPending = maxPending;
    this.waiting = [];
    this.active = null;
    this.worker = null;
    this.nextId = 0;
  }

  render(skin, background) {
    if (this.waiting.length + Number(Boolean(this.active)) >= this.maxPending) {
      return Promise.reject(new Error('Portrait queue is full'));
    }
    return new Promise((resolve, reject) => {
      this.waiting.push({ id: ++this.nextId, skin, background, resolve, reject });
      this.runNext();
    });
  }

  runNext() {
    if (this.active || !this.waiting.length) return;
    if (!this.worker) {
      const worker = this.worker = new Worker(this.workerPath);
      worker.on('message', result => {
        if (worker !== this.worker || result.id !== this.active?.id) return;
        clearTimeout(this.timer);
        const job = this.active;
        this.active = null;
        if (result.error) job.reject(new Error(result.error));
        else job.resolve({ buffer: Buffer.from(result.buffer), timings: result.timings });
        if (!this.waiting.length) worker.unref();
        this.runNext();
      });
      worker.on('error', error => this.failed(worker, error));
      worker.on('exit', code => this.failed(worker, new Error(`Portrait worker exited (${code})`)));
    }
    this.worker.ref();
    this.active = this.waiting.shift();
    this.timer = setTimeout(() => this.failed(this.worker, new Error('Portrait render timed out')), this.timeoutMs);
    this.worker.postMessage({ id: this.active.id, skin: this.active.skin, background: this.active.background });
  }

  failed(worker, error) {
    if (worker !== this.worker) return;
    clearTimeout(this.timer);
    this.worker = null;
    worker.terminate().catch(() => {});
    if (this.active) this.active.reject(error);
    this.active = null;
    this.runNext();
  }

  async close() {
    clearTimeout(this.timer);
    for (const job of [this.active, ...this.waiting].filter(Boolean)) job.reject(new Error('Portrait worker closed'));
    this.active = null;
    this.waiting = [];
    const worker = this.worker;
    this.worker = null;
    if (worker) await worker.terminate();
  }
}

let queue;
const getQueue = () => queue || (queue = new PortraitQueue());
let sequence = 0;
const requests = new Map();
let listening = false;

function attachCluster() {
  if (listening || !cluster.isPrimary) return;
  listening = true;
  cluster.on('message', (worker, message) => {
    if (message?.type !== 'avatar:portrait') return;
    getQueue().render(message.skin, message.background).then(result => {
      if (worker.isConnected()) worker.send({ type: 'avatar:portrait-result', id: message.id, buffer: result.buffer.toString('base64'), timings: result.timings });
    }).catch(error => {
      if (worker.isConnected()) worker.send({ type: 'avatar:portrait-result', id: message.id, error: error.message });
    });
  });
}

function renderHead(skin, background) {
  if (!cluster.isWorker || !process.send) return getQueue().render(skin, background);
  if (!listening) {
    listening = true;
    process.on('message', message => {
      if (message?.type !== 'avatar:portrait-result') return;
      const job = requests.get(message.id);
      if (!job) return;
      requests.delete(message.id);
      clearTimeout(job.timer);
      if (message.error) job.reject(new Error(message.error));
      else job.resolve({ buffer: Buffer.from(message.buffer, 'base64'), timings: message.timings });
    });
  }
  return new Promise((resolve, reject) => {
    const id = `${process.pid}:${++sequence}`;
    const timer = setTimeout(() => { requests.delete(id); reject(new Error('Portrait response timed out')); }, 60000);
    requests.set(id, { resolve, reject, timer });
    process.send({ type: 'avatar:portrait', id, skin, background }, error => {
      if (error) { clearTimeout(timer); requests.delete(id); reject(error); }
    });
  });
}

module.exports = { renderHead, attachCluster, PortraitQueue };
