export function storageArchivePreviewSummary(preview) {
  if (!preview) return null;
  return {
    periodLabel: preview.periodLabel || null,
    orderCount: Number(preview.orderCount || 0),
    orderPackageCount: Number(preview.orderPackageCount || 0),
    supplementFileCount: Number(preview.supplementFileCount || 0),
    managedFileCount: Number(preview.managedFileCount || 0),
    totalBytes: Number(preview.totalBytes || 0),
  };
}
