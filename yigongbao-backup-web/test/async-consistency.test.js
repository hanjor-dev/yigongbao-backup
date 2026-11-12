import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createLatestRequestGuard } from '../src/jobDetailState.js';

const read = (path) => readFileSync(new URL(path, import.meta.url), 'utf8');

test('latest request guard rejects an older response on the same lane', () => {
  const guard = createLatestRequestGuard();
  const first = guard.begin('orders');
  const second = guard.begin('orders');
  assert.equal(guard.isCurrent(first), false);
  assert.equal(guard.isCurrent(second), true);
  assert.equal(guard.isCurrent(guard.begin('accounts')), true);
  assert.equal(guard.isCurrent(second), true);
  guard.invalidate('orders');
  assert.equal(guard.isCurrent(second), false);
});

test('restore detail uses scoped request generations and non-overlapping polling', () => {
  const source = read('../src/composables/useRestoreTasks.js');
  assert.match(source, /createDetailRequestGuard/);
  assert.match(source, /beginTaskRequest\('detail'\)/);
  assert.match(source, /beginOrderRequest\('files'\)/);
  assert.match(source, /isCurrentOrderRequest/);
  assert.match(source, /setTimeout\(async/);
  assert.doesNotMatch(source, /setInterval\(/);
});

test('orders accounts jobs and cold storage only accept their latest list request', () => {
  const source = read('../src/composables/useBackupConsole.js');
  for (const lane of ['orders', 'accounts', 'jobs', 'cold-storage']) {
    assert.match(source, new RegExp(`listRequestGuard\\.begin\\('${lane}'\\)`));
  }
  assert.match(source, /listRequestGuard\.isCurrent\(requestToken\)/);
});

test('large downloads use one-time tickets and never materialize response blobs in console', () => {
  const source = read('../src/composables/useBackupConsole.js');
  const apiSource = read('../src/api.js');
  assert.match(source, /startTicketDownload\('\/download-tickets\/order-packages\//);
  assert.match(source, /startTicketDownload\('\/download-tickets\/supplements\//);
  assert.doesNotMatch(source, /response\.blob\(\)|saveBlob\(|\bbinary\(/);
  assert.match(apiSource, /frame\.src = url/);
  assert.match(apiSource, /24 \* 60 \* 60 \* 1000/);
});
