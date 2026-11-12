import test from 'node:test';
import assert from 'node:assert/strict';

test('multipart upload rejects rather than hanging on 401', async () => {
  const saved = {
    XMLHttpRequest: globalThis.XMLHttpRequest,
    localStorage: globalThis.localStorage,
    window: globalThis.window,
    CustomEvent: globalThis.CustomEvent,
  };
  class UnauthorizedXhr {
    constructor() { this.upload = {}; this.status = 0; this.responseText = ''; }
    open() {}
    setRequestHeader() {}
    send() {
      this.status = 401;
      queueMicrotask(() => this.onload());
    }
  }
  globalThis.XMLHttpRequest = UnauthorizedXhr;
  globalThis.localStorage = { getItem: () => 'token', removeItem: () => {} };
  globalThis.window = { dispatchEvent: () => {} };
  globalThis.CustomEvent = class { constructor(type) { this.type = type; } };
  try {
    const { uploadWithProgress } = await import('../src/api.js');
    await assert.rejects(uploadWithProgress('/upload', {}, () => {}), error => error.code === 'UNAUTHORIZED');
  } finally {
    Object.assign(globalThis, saved);
  }
});
