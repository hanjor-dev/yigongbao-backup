import test from 'node:test';
import assert from 'node:assert/strict';

const access = await import('../src/stores/routeStore.js');

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
