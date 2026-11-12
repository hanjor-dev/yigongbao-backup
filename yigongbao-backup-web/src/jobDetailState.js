/**
 * 为任务详情的异步请求生成作用域令牌，避免关闭或切换后旧响应回写新界面。
 * 任务和订单使用独立版本号，打开订单抽屉不会让任务轮询响应失效。
 */
export function createDetailRequestGuard() {
  let taskId = null;
  let orderId = null;
  let taskVersion = 0;
  let orderVersion = 0;
  const taskRequests = new Map();
  const orderRequests = new Map();

  return {
    openTask(value) {
      taskId = String(value);
      orderId = null;
      taskVersion += 1;
      orderVersion += 1;
      taskRequests.clear();
      orderRequests.clear();
    },
    closeTask() {
      taskId = null;
      orderId = null;
      taskVersion += 1;
      orderVersion += 1;
      taskRequests.clear();
      orderRequests.clear();
    },
    captureTask() {
      return { taskId, version: taskVersion };
    },
    isCurrentTask(token) {
      return Boolean(taskId) && token?.taskId === taskId && token?.version === taskVersion;
    },
    beginTaskRequest(lane) {
      const requestVersion = (taskRequests.get(lane) || 0) + 1;
      taskRequests.set(lane, requestVersion);
      return { taskId, version: taskVersion, lane, requestVersion };
    },
    isCurrentTaskRequest(token) {
      return Boolean(taskId)
        && token?.taskId === taskId
        && token?.version === taskVersion
        && taskRequests.get(token?.lane) === token?.requestVersion;
    },
    openOrder(value) {
      orderId = String(value);
      orderVersion += 1;
      orderRequests.clear();
    },
    closeOrder() {
      orderId = null;
      orderVersion += 1;
      orderRequests.clear();
    },
    captureOrder() {
      return { taskId, orderId, version: orderVersion };
    },
    isCurrentOrder(token) {
      return Boolean(taskId && orderId)
        && token?.taskId === taskId
        && token?.orderId === orderId
        && token?.version === orderVersion;
    },
    beginOrderRequest(lane) {
      const requestVersion = (orderRequests.get(lane) || 0) + 1;
      orderRequests.set(lane, requestVersion);
      return { taskId, orderId, version: orderVersion, lane, requestVersion };
    },
    isCurrentOrderRequest(token) {
      return Boolean(taskId && orderId)
        && token?.taskId === taskId
        && token?.orderId === orderId
        && token?.version === orderVersion
        && orderRequests.get(token?.lane) === token?.requestVersion;
    },
  };
}

/** 为分页列表的并发请求分配单调代次，只允许同一通道最后发出的请求回写。 */
export function createLatestRequestGuard() {
  const versions = new Map();
  return {
    begin(lane = 'default') {
      const version = (versions.get(lane) || 0) + 1;
      versions.set(lane, version);
      return { lane, version };
    },
    isCurrent(token) {
      return Boolean(token) && versions.get(token.lane) === token.version;
    },
    invalidate(lane = 'default') {
      versions.set(lane, (versions.get(lane) || 0) + 1);
    },
    invalidateAll() {
      for (const lane of versions.keys()) this.invalidate(lane);
    },
  };
}

/** 记录某个详情区块最后成功加载的数据键，支持任务与订单区块独立失效。 */
export function createSectionLoadTracker() {
  const loaded = new Map();
  return {
    shouldLoad(section, key) {
      return loaded.get(section) !== String(key);
    },
    markLoaded(section, key) {
      loaded.set(section, String(key));
    },
    reset(section) {
      loaded.delete(section);
    },
    resetAll() {
      loaded.clear();
    },
  };
}

/** 构造服务端文件分页地址，订单抽屉通过可选来源订单参数复用同一接口。 */
export function buildJobFilePageUrl(jobId, current, size, sourceOrderId = null) {
  const params = new URLSearchParams({ current: String(current), size: String(size) });
  if (sourceOrderId != null) params.set('sourceOrderId', String(sourceOrderId));
  return `/admin/jobs/${encodeURIComponent(jobId)}/files?${params.toString()}`;
}

/** 文件表只展示业务订单编号，不以内部来源订单ID降级代替。 */
export function jobFileOrderCode(row) {
  return row?.orderCode || '-';
}

/** 原始文件名缺失时显示空值标记，资产ID仍由独立列展示。 */
export function jobFileOriginalFilename(row) {
  return row?.originalFilename || '-';
}
