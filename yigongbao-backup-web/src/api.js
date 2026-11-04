/**
 * 本地备份管理 API 统一封装。
 * 负责鉴权头、响应信封解析、二进制下载和登录失效事件，页面模块不再重复处理这些细节。
 */
const API_PREFIX = '/api/v1';

export function getAuthToken() {
  return localStorage.getItem('backup-token');
}

export function clearAuth() {
  localStorage.removeItem('backup-token');
  localStorage.removeItem('backup-role');
  window.dispatchEvent(new CustomEvent('backup-auth-expired'));
}

function unauthorizedError(message = '未登录或登录已失效') {
  clearAuth();
  const error = new Error(message);
  error.code = 'UNAUTHORIZED';
  return error;
}

function responseError(body, fallback) {
  const error = new Error(body?.message || body?.code || fallback);
  error.code = body?.code;
  error.requestId = body?.requestId;
  return error;
}

/** 调用 JSON API，并统一处理服务端错误信封。 */
export async function api(path, options = {}) {
  const token = getAuthToken();
  const isFormData = options.body instanceof FormData;
  const headers = {
    ...(isFormData ? {} : { 'Content-Type': 'application/json' }),
    ...(token ? { 'backup-token': token } : {}),
    ...(options.headers || {})
  };
  const response = await fetch(API_PREFIX + path, { ...options, headers });
  if (response.status === 401) throw unauthorizedError();
  const body = await response.json().catch(() => ({}));
  if (!response.ok || (body.code && body.code !== 'OK')) {
    throw responseError(body, '请求失败');
  }
  return body;
}

/** 使用 XMLHttpRequest 暴露 multipart 上传进度；普通 JSON 请求仍使用 fetch。 */
export function uploadWithProgress(path, formData, onProgress) {
  return new Promise((resolve, reject) => {
    const request = new XMLHttpRequest();
    request.open('POST', API_PREFIX + path);
    const token = getAuthToken();
    if (token) request.setRequestHeader('backup-token', token);
    request.upload.onprogress = (event) => {
      if (event.lengthComputable && typeof onProgress === 'function') {
        onProgress(Math.min(100, Math.round(event.loaded * 100 / event.total)));
      }
    };
    request.onerror = () => reject(new Error('文件上传网络异常'));
    request.onabort = () => reject(new Error('文件上传已取消'));
    request.onload = () => {
      let body = {};
      try { body = request.responseText ? JSON.parse(request.responseText) : {}; } catch (_) { }
      if (request.status === 401) { unauthorizedError(); return; }
      if (request.status < 200 || request.status >= 300 || (body.code && body.code !== 'OK')) {
        reject(responseError(body, request.status === 413 ? '补传文件超过系统上传上限' : '文件上传失败'));
        return;
      }
      if (typeof onProgress === 'function') onProgress(100);
      resolve(body);
    };
    request.send(formData);
  });
}

/** 调用文件下载 API，调用者负责读取响应流并触发浏览器保存。 */
export async function binary(path) {
  const token = getAuthToken();
  const response = await fetch(API_PREFIX + path, {
    headers: token ? { 'backup-token': token } : {}
  });
  if (response.status === 401) throw unauthorizedError();
  if (!response.ok) {
    const body = await response.json().catch(() => ({}));
    throw responseError(body, '文件下载失败');
  }
  return response;
}

export function saveBlob(blob, name) {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = name;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  URL.revokeObjectURL(url);
}
