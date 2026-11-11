import { ref } from 'vue';

const ROUTE_KEY = 'backup-current-page';
const ROUTES = new Set(['overview', 'backup', 'restore', 'orders', 'accounts', 'storage', 'storage-volumes', 'cold-storage', 'cleanup', 'configs']);
const QUERY_KEYS = Object.freeze({
  orders: new Set(['backupMode', 'storageAccessible', 'storageIssue']),
  backup: new Set(['jobId', 'finishedFrom', 'finishedTo', 'orderResult']),
  restore: new Set(['jobId']),
  'cold-storage': new Set(['archiveId']),
  storage: new Set(['volumeId']),
  'storage-volumes': new Set(['volumeId']),
  accounts: new Set(['focus']),
  cleanup: new Set(['focus']),
  configs: new Set(['key'])
});

function parseHash(hash) {
  const source = String(hash || '').replace(/^#/, '');
  const [rawPage, rawQuery = ''] = source.split('?', 2);
  const page = ROUTES.has(rawPage) ? rawPage : null;
  const allowed = QUERY_KEYS[page] || new Set();
  const query = {};
  for (const [key, value] of new URLSearchParams(rawQuery)) {
    if (allowed.has(key) && value.length <= 256) query[key] = value;
  }
  return { page, query };
}

function safeQuery(page, candidate) {
  const allowed = QUERY_KEYS[page] || new Set();
  const result = {};
  if (!candidate || typeof candidate !== 'object') return result;
  for (const [key, raw] of Object.entries(candidate)) {
    if (!allowed.has(key) || raw == null) continue;
    const value = String(raw);
    if (value && value.length <= 256) result[key] = value;
  }
  return result;
}

function hashFor(page, query) {
  const params = new URLSearchParams(query);
  return `#${page}${params.size ? `?${params.toString()}` : ''}`;
}

/** 轻量结构化 hash 路由：目标页与查询键均使用白名单，查询参数可一次性消费。 */
export function createRouteStore() {
  const parsed = parseHash(window.location.hash);
  const saved = localStorage.getItem(ROUTE_KEY);
  const initial = parsed.page || (ROUTES.has(saved) ? saved : 'orders');
  const page = ref(initial);
  const routeQuery = ref(parsed.page === initial ? parsed.query : {});

  function syncHash() {
    window.history.replaceState(null, '', hashFor(page.value, routeQuery.value));
  }

  function navigate(target, query) {
    const next = ROUTES.has(target) ? target : 'orders';
    const nextQuery = query === undefined && next === page.value ? routeQuery.value : safeQuery(next, query);
    page.value = next;
    routeQuery.value = { ...nextQuery };
    localStorage.setItem(ROUTE_KEY, next);
    syncHash();
  }

  function consumeRouteQuery(keys) {
    const wanted = new Set(Array.isArray(keys) ? keys : [keys]);
    const consumed = {};
    const remaining = {};
    for (const [key, value] of Object.entries(routeQuery.value)) {
      if (wanted.has(key)) consumed[key] = value;
      else remaining[key] = value;
    }
    routeQuery.value = remaining;
    syncHash();
    return consumed;
  }

  function onHashChange() {
    const next = parseHash(window.location.hash);
    if (!next.page) return;
    page.value = next.page;
    routeQuery.value = next.query;
    localStorage.setItem(ROUTE_KEY, next.page);
  }

  return { page, routeQuery, navigate, consumeRouteQuery, onHashChange };
}

export const overviewRouteContract = Object.freeze({ routes: ROUTES, queryKeys: QUERY_KEYS });
