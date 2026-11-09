import { ref } from 'vue';

const ROUTE_KEY = 'backup-current-page';
const ROUTES = new Set(['overview', 'audit', 'backup', 'restore', 'orders', 'accounts', 'storage', 'storage-volumes', 'cold', 'cleanup', 'configs']);

/** 页面路由仓库：当前项目无需引入大型路由依赖，使用 hash 与 localStorage 保留刷新后的页面。 */
export function createRouteStore() {
  const saved = localStorage.getItem(ROUTE_KEY) || window.location.hash.slice(1);
  const page = ref(ROUTES.has(saved) ? saved : 'orders');

  function navigate(target) {
    const next = ROUTES.has(target) ? target : 'orders';
    page.value = next;
    localStorage.setItem(ROUTE_KEY, next);
    window.history.replaceState(null, '', `#${next}`);
  }

  function onHashChange() {
    const next = window.location.hash.slice(1);
    if (ROUTES.has(next)) {
      page.value = next;
      localStorage.setItem(ROUTE_KEY, next);
    }
  }

  return { page, navigate, onHashChange };
}
