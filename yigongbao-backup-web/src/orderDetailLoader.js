/**
 * 独立加载订单详情区块，避免某个辅助接口失败时丢弃已经成功返回的补传历史。
 */
export async function loadOrderDetailSections(orderId, request) {
  const id = encodeURIComponent(String(orderId));
  const sections = [
    { key: 'assets', label: '关联文件', path: `/orders/${id}/assets`, fallback: [] },
    { key: 'supplements', label: '补传历史', path: `/orders/${id}/supplements`, fallback: [] },
    { key: 'storageLocation', label: '存储位置', path: `/orders/${id}/package-location`, fallback: null },
  ];
  const settled = await Promise.allSettled(sections.map((section) => request(section.path)));
  const output = { assets: [], supplements: [], storageLocation: null, errors: [] };

  settled.forEach((result, index) => {
    const section = sections[index];
    if (result.status === 'fulfilled') {
      output[section.key] = result.value?.data ?? section.fallback;
      return;
    }
    output[section.key] = section.fallback;
    output.errors.push({
      section: section.label,
      message: result.reason?.message || '加载失败',
    });
  });

  return output;
}
