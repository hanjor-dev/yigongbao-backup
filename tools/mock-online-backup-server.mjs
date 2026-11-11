import http from 'node:http';
import { createHash } from 'node:crypto';

const port = Number(process.env.MOCK_BACKUP_PORT || 18081);
const orders = Array.from({ length: 10 }, (_, index) => {
  const orderId = 900001 + index;
  const content = Buffer.from(`mock-order-${orderId}`, 'utf8');
  return { orderId, content, sha256: createHash('sha256').update(content).digest('hex') };
});
const manifestDigest = (orderId) => createHash('sha256').update(`manifest-${orderId}`).digest('hex');
const envelope = (data) => JSON.stringify({ code: 'OK', message: '操作成功', requestId: 'mock-local', data });

function readBody(request) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    request.on('data', (chunk) => chunks.push(chunk));
    request.on('end', () => {
      try { resolve(chunks.length ? JSON.parse(Buffer.concat(chunks).toString('utf8')) : {}); }
      catch (error) { reject(error); }
    });
    request.on('error', reject);
  });
}

function json(response, status, data) {
  response.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8' });
  response.end(envelope(data));
}

const server = http.createServer(async (request, response) => {
  const url = new URL(request.url, `http://127.0.0.1:${port}`);
  try {
    if (request.method === 'POST' && url.pathname === '/internal/backup/v1/candidates/search') {
      const body = await readBody(request);
      if (!body.orderCreatedBefore) throw new Error('orderCreatedBefore is required');
      const cutoff = Date.parse(body.orderCreatedBefore);
      const offset = body.cursor ? Number(body.cursor) : 0;
      const size = Math.min(Number(body.pageSize || 5), 10);
      const eligibleOrders = Date.parse('2026-10-01T00:00:00Z') < cutoff ? orders : [];
      const items = eligibleOrders.slice(offset, offset + size).map((order, index) => ({
        eventId: offset + index + 1, orderId: order.orderId, generation: 1,
        eligibleAt: '2026-11-01T00:00:00+08:00', orderCreatedAt: '2026-10-01T00:00:00+08:00',
        eventStatus: 'ELIGIBLE'
      }));
      const next = offset + items.length;
      json(response, 200, { items, nextCursor: next < eligibleOrders.length ? String(next) : '', hasMore: next < eligibleOrders.length, highWatermark: '10' });
      return;
    }
    const manifestMatch = url.pathname.match(/^\/internal\/backup\/v1\/orders\/(\d+)\/manifest$/);
    if (request.method === 'GET' && manifestMatch) {
      const orderId = Number(manifestMatch[1]);
      const order = orders.find((item) => item.orderId === orderId);
      if (!order) { json(response, 404, null); return; }
      const digest = manifestDigest(orderId);
      json(response, 200, {
        manifestRevision: digest, manifestJcsSha256: digest, orderId,
        order: { id: orderId, orderCode: `MOCK-${orderId}`, status: 10, isClassicCase: 0, createTime: '2026-10-01T00:00:00' },
        assets: [{ assetIdentity: String(orderId), sourceFileId: orderId, assetType: 'ORDER_FILE',
          originalFilename: `mock-${orderId}.txt`, size: order.content.length, sha256: order.sha256,
          sourceObject: { platform: 'COS', bucket: 'mock', objectKey: `mock/${orderId}.txt`, versionIdentity: 'v1', url: `http://127.0.0.1:${port}/mock-cos/${orderId}` },
          relations: [{ relationKind: 'ORDER_FILE', sourceRecordId: String(orderId), relationVersion: '1', relationHash: digest, originalFileId: String(orderId) }]
        }], downloadGrant: {}
      });
      return;
    }
    if (request.method === 'GET' && url.pathname.startsWith('/mock-cos/')) {
      const orderId = Number(url.pathname.split('/').at(-1));
      const order = orders.find((item) => item.orderId === orderId);
      if (!order) { response.writeHead(404).end(); return; }
      response.writeHead(200, { 'Content-Type': 'application/octet-stream', 'Content-Length': order.content.length });
      response.end(order.content);
      return;
    }
    if (request.method === 'POST' && /\/manifest\/[0-9a-f]{64}\/confirm$/.test(url.pathname)) {
      await readBody(request); json(response, 200, { confirmed: true }); return;
    }
    if (request.method === 'PUT' && /^\/internal\/backup\/v1\/receipts\/\d+$/.test(url.pathname)) {
      const body = await readBody(request);
      console.log('receipt', url.pathname.split('/').at(-1), body.packageSha256);
      json(response, 200, { receiptId: Date.now(), receiptVersion: 1, status: 'ACTIVE', stateVersion: 0 });
      return;
    }
    if (request.method === 'POST' && url.pathname === '/internal/backup/v1/candidates/reconcile') {
      await readBody(request); json(response, 200, { scanned: 10, repaired: 0, cancelled: 0, deleted: 0 }); return;
    }
    json(response, 404, null);
  } catch (error) {
    response.writeHead(500, { 'Content-Type': 'application/json' });
    response.end(JSON.stringify({ code: 'MOCK_ERROR', message: error.message }));
  }
});

server.listen(port, '127.0.0.1', () => {
  console.log(`Mock online/COS service: http://127.0.0.1:${port}`);
  console.log('Set online.base-url to this address; use backup.schedule.max-orders-per-run for local batch sizing.');
});
