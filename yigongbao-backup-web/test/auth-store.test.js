import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';

const root = path.resolve(import.meta.dirname, '..');

test('logout invalidates the server session before clearing local authentication', () => {
  const source = fs.readFileSync(path.join(root, 'src', 'stores', 'authStore.js'), 'utf8');
  assert.match(source, /async function logout\(\)/);
  assert.match(source, /await api\(['"]\/auth\/logout['"],\s*\{\s*method:\s*['"]POST['"]/s);
  assert.match(source, /finally\s*\{[\s\S]*clearAuth\(\)/);
});
