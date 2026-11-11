function positiveInteger(value, fallback) {
  const number = Number(value);
  return Number.isInteger(number) && number > 0 ? number : fallback;
}

/**
 * 统一封存详情的新旧接口结构。
 * 新后端返回服务端分页对象；旧后端返回完整 items 数组，前端在兼容期按请求页切片。
 */
export function normalizeStorageArchiveDetail(detail, requestedCurrent = 1, requestedSize = 20) {
  if (!detail) return null;
  const current = positiveInteger(requestedCurrent, 1);
  const size = positiveInteger(requestedSize, 20);

  if (Array.isArray(detail.items)) {
    const total = detail.items.length;
    const pages = total === 0 ? 0 : Math.ceil(total / size);
    const normalizedCurrent = pages === 0 ? 1 : Math.min(current, pages);
    const start = (normalizedCurrent - 1) * size;
    return {
      ...detail,
      items: {
        records: detail.items.slice(start, start + size),
        total,
        current: normalizedCurrent,
        size,
        pages,
      },
    };
  }

  const page = detail.items || {};
  const normalizedSize = positiveInteger(page.size, size);
  const total = Math.max(0, Number(page.total) || 0);
  return {
    ...detail,
    items: {
      ...page,
      records: Array.isArray(page.records) ? page.records : [],
      total,
      current: positiveInteger(page.current, current),
      size: normalizedSize,
      pages: Math.max(0, Number(page.pages) || (total === 0 ? 0 : Math.ceil(total / normalizedSize))),
    },
  };
}
