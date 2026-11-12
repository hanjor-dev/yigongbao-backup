import { ref } from 'vue';

const ROUTE_KEY = 'backup-current-page';
const ROUTES = new Set(['overview', 'backup', 'restore', 'orders', 'accounts', 'storage', 'storage-volumes', 'cold-storage', 'cleanup', 'configs']);
const NAVIGATION_GROUPS = Object.freeze([
  { title: '工作台', items: [{ key: 'overview', label: '管理概览', icon: 'DataAnalysis', admin: true }, { key: 'orders', label: '订单文件', icon: 'Files' }] },
  { title: '运营中心', items: [{ key: 'backup', label: '订单备份', icon: 'Download', admin: true }, { key: 'restore', label: '线上恢复', icon: 'Upload', admin: true }, { key: 'cold-storage', label: '冷备管理', icon: 'Box', admin: true }, { key: 'cleanup', label: 'COS 清理', icon: 'Delete', admin: true }] },
  { title: '系统管理', items: [{ key: 'accounts', label: '本地账号', icon: 'User', admin: true }, { key: 'storage-volumes', label: '存储卷管理', icon: 'Coin', admin: true }, { key: 'configs', label: '系统配置', icon: 'Setting', admin: true }] }
]);
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

export function defaultPageForRole(role) {
  return role === 'ADMIN' ? 'overview' : 'orders';
}

export function canAccessPage(role, page) {
  return ROUTES.has(page) && (role === 'ADMIN' || page === 'orders');
}

export function navigationGroupsForRole(role) {
  return NAVIGATION_GROUPS.map((group) => ({
    ...group,
    items: group.items.filter((item) => role === 'ADMIN' || !item.admin)
  })).filter((group) => group.items.length > 0);
}

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
export function createRouteStore(roleProvider = () => 'ADMIN') {
  const currentRole = () => typeof roleProvider === 'function'
    ? roleProvider()
    : (roleProvider?.value || roleProvider || 'USER');
  const accessiblePage = (candidate) => {
    if (!ROUTES.has(candidate)) return 'orders';
    return canAccessPage(currentRole(), candidate) ? candidate : defaultPageForRole(currentRole());
  };
  const parsed = parseHash(window.location.hash);
  const saved = localStorage.getItem(ROUTE_KEY);
  const requested = parsed.page || (ROUTES.has(saved) ? saved : 'orders');
  const initial = accessiblePage(requested);
  const page = ref(initial);
  const routeQuery = ref(parsed.page === initial ? parsed.query : {});

  function syncHash() {
    window.history.replaceState(null, '', hashFor(page.value, routeQuery.value));
  }

  function navigate(target, query) {
    const next = accessiblePage(target);
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
    const accessible = accessiblePage(next.page);
    page.value = accessible;
    routeQuery.value = accessible === next.page ? next.query : {};
    localStorage.setItem(ROUTE_KEY, accessible);
    if (accessible !== next.page) syncHash();
  }

  if (parsed.page && parsed.page !== initial) syncHash();

  return { page, routeQuery, navigate, consumeRouteQuery, onHashChange };
}

export const overviewRouteContract = Object.freeze({ routes: ROUTES, queryKeys: QUERY_KEYS });
