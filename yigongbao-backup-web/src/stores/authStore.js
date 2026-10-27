import { computed, ref } from 'vue';
import { api, clearAuth } from '../api.js';

/**
 * 本地认证状态仓库。
 * token、角色均持久化在 localStorage，保证刷新页面后仍能恢复登录态；
 * 本地密码和启停状态只调用本地接口，不会回写线上医工宝。
 */
export function createAuthStore() {
  const token = ref(localStorage.getItem('backup-token'));
  const role = ref(localStorage.getItem('backup-role') || 'USER');
  const isAdmin = computed(() => role.value === 'ADMIN');

  async function login(username, password) {
    const result = await api('/auth/login', {
      method: 'POST',
      body: JSON.stringify({ username, password })
    });
    if (!result.data?.token) throw new Error(result.message || '登录失败');
    token.value = result.data.token;
    role.value = result.data.roleCode || 'USER';
    localStorage.setItem('backup-token', token.value);
    localStorage.setItem('backup-role', role.value);
    return result;
  }

  async function changePassword(password) {
    if (String(password || '').length < 8) throw new Error('本地密码至少八位');
    await api('/auth/password', { method: 'PUT', body: JSON.stringify({ password }) });
  }

  function logout() {
    clearAuth();
    token.value = null;
    role.value = 'USER';
  }

  function handleAuthExpired() {
    token.value = null;
    role.value = 'USER';
  }

  return { token, role, isAdmin, login, changePassword, logout, handleAuthExpired };
}
