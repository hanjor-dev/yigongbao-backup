import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const access = await import('../src/stores/routeStore.js');
const read = (path) => readFileSync(new URL(path, import.meta.url), 'utf8');

test('role landing page sends administrators to overview and users to orders', () => {
  assert.equal(typeof access.defaultPageForRole, 'function');
  assert.equal(access.defaultPageForRole('ADMIN'), 'overview');
  assert.equal(access.defaultPageForRole('USER'), 'orders');
  assert.equal(access.defaultPageForRole('UNKNOWN'), 'orders');
});

test('ordinary users can access only the orders page while administrators can access every console page', () => {
  assert.equal(typeof access.canAccessPage, 'function');
  assert.equal(access.canAccessPage('USER', 'orders'), true);
  assert.equal(access.canAccessPage('USER', 'overview'), false);
  assert.equal(access.canAccessPage('USER', 'backup'), false);
  assert.equal(access.canAccessPage('ADMIN', 'overview'), true);
  assert.equal(access.canAccessPage('ADMIN', 'configs'), true);
});

test('ordinary users receive one orders menu without empty menu groups', () => {
  assert.equal(typeof access.navigationGroupsForRole, 'function');
  const userGroups = access.navigationGroupsForRole('USER');
  assert.equal(userGroups.length, 1);
  assert.deepEqual(userGroups[0].items.map((item) => item.key), ['orders']);

  const adminItems = access.navigationGroupsForRole('ADMIN').flatMap((group) => group.items);
  assert.deepEqual(adminItems.map((item) => item.key), [
    'overview', 'orders', 'backup', 'restore', 'cold-storage', 'cleanup',
    'accounts', 'storage-volumes', 'configs'
  ]);
  assert.equal(adminItems.find((item) => item.key === 'cleanup')?.label, '云端清理');
  assert.match(read('../src/views/CleanupView.vue'), /<h2>云端清理<\/h2>/);
});

test('route store redirects ordinary users away from admin pages including direct hash access', () => {
  const previousLocalStorage = globalThis.localStorage;
  const previousWindow = globalThis.window;
  const values = new Map();
  globalThis.localStorage = {
    getItem: (key) => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, value)
  };
  globalThis.window = {
    location: { hash: '#overview' },
    history: { replaceState: (_state, _title, hash) => { globalThis.window.location.hash = hash; } }
  };

  try {
    const route = access.createRouteStore(() => 'USER');
    assert.equal(route.page.value, 'orders');
    assert.equal(globalThis.window.location.hash, '#orders');

    route.navigate('configs');
    assert.equal(route.page.value, 'orders');

    globalThis.window.location.hash = '#backup';
    route.onHashChange();
    assert.equal(route.page.value, 'orders');
    assert.equal(globalThis.window.location.hash, '#orders');
  } finally {
    if (previousLocalStorage === undefined) delete globalThis.localStorage;
    else globalThis.localStorage = previousLocalStorage;
    if (previousWindow === undefined) delete globalThis.window;
    else globalThis.window = previousWindow;
  }
});

test('left navigation stays fixed while the main content scrolls', () => {
  const styles = read('../src/style-production.css');
  const asideRule = styles.match(/\.console-aside\s*\{([^}]*)\}/)?.[1] || '';

  assert.match(asideRule, /position:sticky/);
  assert.match(asideRule, /top:0/);
  assert.match(asideRule, /height:100vh/);
  assert.match(asideRule, /overflow-y:auto/);
  assert.match(asideRule, /align-self:flex-start/);
});
