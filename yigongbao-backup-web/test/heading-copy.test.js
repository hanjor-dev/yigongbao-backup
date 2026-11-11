import test from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import { build } from 'vite';

const root = path.resolve(import.meta.dirname, '..');

test('production UI omits English eyebrow copy above page and detail titles', async () => {
  const result = await build({ root, logLevel: 'silent', build: { write: false } });
  const builds = Array.isArray(result) ? result : [result];
  const output = builds.flatMap((entry) => entry.output || []);
  const bundledText = output.map((entry) => entry.code || entry.source || '').join('\n');

  for (const eyebrow of [
    'LOCAL DATA VAULT',
    'STORAGE VOLUMES',
    'ONLINE RESTORE',
    'OPERATIONS',
    'ORDER ASSETS',
    'SYSTEM CONTROL',
    'COLD ARCHIVE',
    'OFFLINE MEDIA VAULT',
    'CLOUD RETENTION',
    'AUTOMATIC BACKUP',
    'IDENTITY',
    'TASK DETAIL',
    'RESTORE TASK DETAIL',
    'SELECTED ORDER'
  ]) {
    assert.equal(bundledText.includes(`\`${eyebrow}\``), false, `${eyebrow} should not be rendered above a title`);
  }
});
