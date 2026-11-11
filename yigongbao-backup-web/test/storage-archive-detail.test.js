import test from 'node:test';
import assert from 'node:assert/strict';

async function loadNormalizer() {
  try {
    return (await import('../src/storageArchiveDetail.js')).normalizeStorageArchiveDetail;
  } catch (_) {
    return undefined;
  }
}

test('legacy archive item arrays are converted into a real requested page', async () => {
  const normalizeStorageArchiveDetail = await loadNormalizer();
  assert.equal(typeof normalizeStorageArchiveDetail, 'function', 'archive detail response normalizer must exist');

  const legacyItems = Array.from({ length: 45 }, (_, index) => ({ id: String(index + 1) }));
  const detail = normalizeStorageArchiveDetail({ archive: { id: 'archive-1' }, items: legacyItems }, 2, 20);

  assert.equal(detail.items.total, 45);
  assert.equal(detail.items.current, 2);
  assert.equal(detail.items.size, 20);
  assert.equal(detail.items.pages, 3);
  assert.deepEqual(detail.items.records.map((item) => item.id),
    Array.from({ length: 20 }, (_, index) => String(index + 21)));
});

test('server paged archive details keep their records and numeric pagination metadata', async () => {
  const normalizeStorageArchiveDetail = await loadNormalizer();
  assert.equal(typeof normalizeStorageArchiveDetail, 'function', 'archive detail response normalizer must exist');

  const records = [{ id: '41' }, { id: '42' }];
  const detail = normalizeStorageArchiveDetail({
    archive: { id: 'archive-1' },
    items: { records, total: '42', current: '3', size: '20', pages: '3' },
  }, 1, 20);

  assert.deepEqual(detail.items.records, records);
  assert.equal(detail.items.total, 42);
  assert.equal(detail.items.current, 3);
  assert.equal(detail.items.size, 20);
  assert.equal(detail.items.pages, 3);
});
