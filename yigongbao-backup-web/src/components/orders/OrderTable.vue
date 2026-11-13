<script setup>
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useBackupContext } from '../../composables/useBackupContext.js';
import { formatJobOrderCreatedAt, hotReplicaPresentation, incompleteBackupTooltip, offlineStorageSummary, stickyTableScrollbarState, storageMountState } from '../../taskCenter.js';

/* finesse · component: order-file-table · register=product
 * states: populated · empty · selected · unavailable · uploading
 * tokens: inherited (Element Plus CSS variables) */
const {
  orders,
  selectedOrder,
  selectedIds,
  supplementUpload,
  selectOrder,
  toggleSelected,
  downloadOrder,
  uploadSupplement,
  storageState,
} = useBackupContext();
const bytes = (value) => { const n = Number(value || 0); if (n < 1024) return `${n} B`; if (n < 1048576) return `${(n / 1024).toFixed(2)} KB`; if (n < 1073741824) return `${(n / 1048576).toFixed(2)} MB`; return `${(n / 1073741824).toFixed(2)} GB`; };
const tableShell = ref(null);
const tableRef = ref(null);
const stickyScrollbar = ref(null);
const stickyScrollbarVisible = ref(false);
const stickyScrollbarStyle = ref({ left: '0px', width: '0px' });
const stickyTrackWidth = ref(0);
let resizeObserver;
let updateTimer = 0;
let syncingFromTable = false;

function tableScroller() {
  return tableShell.value?.querySelector('.el-table__body-wrapper .el-scrollbar__wrap') || null;
}

function updateStickyScrollbar() {
  updateTimer = 0;
  const shell = tableShell.value;
  const scroller = tableScroller();
  if (!shell || !scroller) {
    stickyScrollbarVisible.value = false;
    return;
  }
  const tableRect = shell.getBoundingClientRect();
  const scrollerRect = scroller.getBoundingClientRect();
  const state = stickyTableScrollbarState({
    scrollWidth: scroller.scrollWidth,
    clientWidth: scroller.clientWidth,
    tableTop: tableRect.top,
    tableBottom: tableRect.bottom,
    viewportHeight: window.innerHeight,
  });
  const left = Math.max(0, scrollerRect.left);
  const right = Math.min(window.innerWidth, scrollerRect.right);
  stickyScrollbarVisible.value = state.visible && right > left;
  stickyTrackWidth.value = state.trackWidth;
  stickyScrollbarStyle.value = { left: `${left}px`, width: `${Math.max(0, right - left)}px` };
  if (stickyScrollbar.value && Math.abs(stickyScrollbar.value.scrollLeft - scroller.scrollLeft) > 1) {
    stickyScrollbar.value.scrollLeft = scroller.scrollLeft;
  }
}

function scheduleStickyScrollbarUpdate() {
  if (updateTimer) return;
  updateTimer = window.setTimeout(updateStickyScrollbar, 16);
}

function syncFromTable({ scrollLeft = 0 } = {}) {
  const scrollbar = stickyScrollbar.value;
  if (!scrollbar || Math.abs(scrollbar.scrollLeft - scrollLeft) <= 1) return;
  syncingFromTable = true;
  scrollbar.scrollLeft = scrollLeft;
  syncingFromTable = false;
}

function syncFromStickyScrollbar() {
  if (syncingFromTable || !stickyScrollbar.value) return;
  tableRef.value?.setScrollLeft(stickyScrollbar.value.scrollLeft);
}

async function observeTableSize() {
  await nextTick();
  resizeObserver?.disconnect();
  const scroller = tableScroller();
  if (typeof ResizeObserver !== 'undefined' && tableShell.value && scroller) {
    resizeObserver = new ResizeObserver(scheduleStickyScrollbarUpdate);
    resizeObserver.observe(tableShell.value);
    resizeObserver.observe(scroller);
    if (scroller.firstElementChild) resizeObserver.observe(scroller.firstElementChild);
  }
  scheduleStickyScrollbarUpdate();
}

function uploadOrderFile(row, file) {
  if (!file?.raw) return;
  selectOrder(row);
  uploadSupplement({ target: { files: [file.raw] } });
}

onMounted(() => {
  window.addEventListener('scroll', scheduleStickyScrollbarUpdate, { passive: true });
  window.addEventListener('resize', scheduleStickyScrollbarUpdate, { passive: true });
  observeTableSize();
});
watch(orders, observeTableSize, { flush: 'post' });
onBeforeUnmount(() => {
  window.removeEventListener('scroll', scheduleStickyScrollbarUpdate);
  window.removeEventListener('resize', scheduleStickyScrollbarUpdate);
  resizeObserver?.disconnect();
  if (updateTimer) window.clearTimeout(updateTimer);
});
</script>
<template>
  <div ref="tableShell" class="order-table-shell">
    <el-table ref="tableRef" :data="orders" class="data-table" row-key="id" highlight-current-row @row-click="selectOrder" @scroll="syncFromTable">
    <el-table-column label="选择" width="62" align="center"><template #default="{ row }"><el-tooltip :disabled="row.backupMode !== 'METADATA_SIMULATION' && row.storageAccessible !== false" :content="row.storageAccessible === false ? (row.storageUnavailableReason || '存储卷不可访问') : '模拟备份不可用于线上恢复'"><el-checkbox :disabled="row.backupMode === 'METADATA_SIMULATION' || row.storageAccessible === false" :model-value="selectedIds.includes(row.id)" @click.stop @change="toggleSelected(row)" /></el-tooltip></template></el-table-column>
    <el-table-column label="订单号" min-width="210"><template #default="{ row }"><div class="order-code">{{ row.orderCode || row.sourceOrderId }}</div><small class="muted">快照 #{{ row.id }}</small><el-tag v-if="row.backupMode === 'METADATA_SIMULATION'" size="small" type="warning" effect="plain" class="simulation-tag">模拟备份</el-tag><el-tooltip v-if="incompleteBackupTooltip(row)" :content="incompleteBackupTooltip(row)" placement="top" :show-after="250"><el-tag size="small" type="warning" effect="dark">文件可能不完整</el-tag></el-tooltip></template></el-table-column>
    <el-table-column label="创建时间" width="168"><template #default="{ row }"><time class="table-time" :datetime="row.orderCreatedAt">{{ formatJobOrderCreatedAt(row.orderCreatedAt) }}</time></template></el-table-column>
    <el-table-column label="机构 / 医院" width="170" show-overflow-tooltip><template #default="{ row }"><div>{{ row.orgName || '-' }}</div><small class="muted">{{ row.hospitalName || '-' }}</small></template></el-table-column>
    <el-table-column label="患者 / 医生" width="120" show-overflow-tooltip><template #default="{ row }"><div>{{ row.patientName || '-' }}</div><small class="muted">医生：{{ row.doctorName || '-' }}</small></template></el-table-column>
    <el-table-column label="文件规模" width="160"><template #default="{ row }"><strong>{{ row.assetCount ?? row.fileCount ?? 0 }}</strong><span class="muted"> 个 · {{ bytes(row.totalBytes ?? row.fileBytes) }}</span></template></el-table-column>
    <el-table-column label="存储卷 / 位置" min-width="300" show-overflow-tooltip><template #default="{ row }"><div>{{ row.storageVolumeName || row.storageVolumeId || '-' }} <status-badge v-if="row.storageTier" :value="row.storageTier" /> <status-badge :value="storageMountState(row)" /></div><small class="muted">{{ row.storageKey || '未记录位置' }}</small><small v-if="row.storageAccessible === false" class="muted">{{ row.storageUnavailableReason || '存储卷不可访问' }}</small><small v-if="row.storageDiagnosticReason" class="muted">副本诊断：{{ row.storageDiagnosticReason }}</small><small v-if="row.storageArchiveNote" class="muted">封存：{{ row.storageArchiveNote }}</small><small v-if="row.storageAccessible === false && offlineStorageSummary(row)" class="muted">冷备定位：{{ offlineStorageSummary(row) }}</small></template></el-table-column>
    <el-table-column label="云端" width="100"><template #default="{ row }"><status-badge :value="row.cloudRollup" /></template></el-table-column>
    <el-table-column label="热备" width="112"><template #default="{ row }"><div class="replica-state"><status-badge :value="hotReplicaPresentation(row).rollup" /><el-tag v-if="hotReplicaPresentation(row).unreadable" size="small" type="danger" effect="plain">不可读</el-tag></div></template></el-table-column>
    <el-table-column label="冷备副本" width="110"><template #default="{ row }"><status-badge :value="row.coldRollup" /></template></el-table-column>
    <el-table-column label="操作" width="240" fixed="right" align="center">
      <template #default="{ row }">
        <div class="order-actions" @click.stop>
          <el-button link type="primary" @click.stop="selectOrder(row)">详情</el-button>
          <el-button link type="primary" :disabled="!storageState(row).canDownload" @click.stop="downloadOrder(row)">下载</el-button>
          <el-upload
            :auto-upload="false"
            :show-file-list="false"
            :disabled="supplementUpload.status === 'UPLOADING' || supplementUpload.status === 'PROCESSING'"
            :on-change="(file) => uploadOrderFile(row, file)"
          >
            <el-button
              link
              type="primary"
              :loading="selectedOrder?.id === row.id && supplementUpload.status === 'UPLOADING'"
              :disabled="supplementUpload.status === 'UPLOADING' || supplementUpload.status === 'PROCESSING'"
            >上传工程文件</el-button>
          </el-upload>
        </div>
      </template>
    </el-table-column>
    <template #empty><empty-state message="暂无订单，请调整筛选条件" /></template>
    </el-table>
    <div
      v-show="stickyScrollbarVisible"
      ref="stickyScrollbar"
      class="order-table-sticky-scrollbar"
      :style="stickyScrollbarStyle"
      aria-label="订单列表横向滚动"
      tabindex="0"
      @scroll="syncFromStickyScrollbar"
    >
      <div class="order-table-sticky-scrollbar__track" :style="{ width: `${stickyTrackWidth}px` }" aria-hidden="true"></div>
    </div>
  </div>
</template>

<style scoped>
.order-table-shell { min-width: 0; }
.order-table-sticky-scrollbar { position: fixed; bottom: 0; z-index: 30; box-sizing: border-box; height: 18px; overflow-x: auto; overflow-y: hidden; overscroll-behavior-x: contain; border-top: 1px solid var(--el-border-color-lighter); background: var(--el-bg-color); box-shadow: 0 -4px 12px rgb(31 45 61 / 8%); scrollbar-color: var(--el-border-color-darker) var(--el-fill-color-light); }
.order-table-sticky-scrollbar__track { height: 1px; }
.order-actions { display: flex; align-items: center; justify-content: center; gap: 8px; white-space: nowrap; }
.order-actions :deep(.el-upload) { display: inline-flex; }
.order-actions :deep(.el-button) { min-height: 44px; margin-left: 0; }
.replica-state { display: inline-flex; flex-direction: column; align-items: flex-start; gap: 4px; }
</style>
