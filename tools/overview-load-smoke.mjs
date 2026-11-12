const baseUrl = process.env.BACKUP_LOAD_BASE_URL;
const token = process.env.BACKUP_LOAD_TOKEN;
const concurrency = Math.max(1, Number(process.env.BACKUP_LOAD_CONCURRENCY || 10));
const requests = Math.max(concurrency, Number(process.env.BACKUP_LOAD_REQUESTS || 200));
if (!baseUrl || !token) throw new Error('需要 BACKUP_LOAD_BASE_URL 和 BACKUP_LOAD_TOKEN');

const timings = [];
let next = 0;
let failures = 0;
async function worker() {
  while (true) {
    const index = next++;
    if (index >= requests) return;
    const started = performance.now();
    try {
      const response = await fetch(`${baseUrl.replace(/\/$/, '')}/api/v1/admin/overview`, {
        headers: { 'backup-token': token },
      });
      const body = await response.json().catch(() => ({}));
      if (!response.ok || (body.code && body.code !== 'OK')) failures++;
    } catch { failures++; }
    timings.push(performance.now() - started);
  }
}
await Promise.all(Array.from({ length: concurrency }, worker));
timings.sort((a, b) => a - b);
const percentile = p => timings[Math.min(timings.length - 1, Math.floor(timings.length * p))] || 0;
console.log(JSON.stringify({ requests, concurrency, failures, p50Ms: percentile(.5).toFixed(1), p95Ms: percentile(.95).toFixed(1), p99Ms: percentile(.99).toFixed(1) }, null, 2));
if (failures) process.exitCode = 1;
