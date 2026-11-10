<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue';
import { useBackupContext } from '../composables/useBackupContext.js';
import VolumeArchiveDetail from '../components/storage/VolumeArchiveDetail.vue';
import ColdVolumeMountDialog from '../components/storage/ColdVolumeMountDialog.vue';

const { coldStorageArchives, selectedStorageArchive, coldStorageLoading, storageVolumes, storageCandidates,
  loadColdStorage, openColdStorageArchive, scanColdMountCandidates, confirmColdVolumeMounted,
  prepareColdVolumeOffline, abandonStorageArchive, navigate } = useBackupContext();
const coldVolumes = computed(() => storageVolumes.value.filter((volume) => volume.tier === 'COLD'));
const activeArchives = computed(() => coldStorageArchives.value.filter((item) => ['REQUESTED', 'SWITCHED', 'MANIFEST_FROZEN', 'VERIFYING', 'COMMITTING'].includes(item.archive?.phase)));
const failedArchives = computed(() => coldStorageArchives.value.filter((item) => ['PREPARE_FAILED', 'VERIFY_FAILED', 'COMMIT_FAILED', 'CANCELLED'].includes(item.archive?.phase)));
const onlineCold = computed(() => coldVolumes.value.filter((volume) => volume.mountStatus === 'ONLINE'));
const mountDialog = ref(false);
const mountVolume = ref(null);
let refreshTimer = null;
const bytes = (value) => { const size = Number(value || 0); if (size < 1048576) return `${(size / 1024).toFixed(1)} KB`; if (size < 1073741824) return `${(size / 1048576).toFixed(1)} MB`; return `${(size / 1073741824).toFixed(2)} GB`; };
const phaseType = (phase) => ({ SUCCEEDED: 'success', PREPARE_FAILED: 'danger', VERIFY_FAILED: 'danger', COMMIT_FAILED: 'danger', CANCELLED: 'info', VERIFYING: 'warning', COMMITTING: 'warning' })[phase] || 'info';
const progress = (item) => { const total = Number(item.archive?.managedFileCount || 0); if (!total) return 0; return item.archive?.phase === 'SUCCEEDED' ? 100 : Math.min(99, Math.round(Number(item.verifiedFileCount || 0) * 100 / total)); };

async function refresh() {
  await loadColdStorage();
  if (selectedStorageArchive.value?.archive?.id) await openColdStorageArchive(selectedStorageArchive.value.archive.id, true);
}
async function openMount(volume) {
  mountVolume.value = volume; mountDialog.value = true; await scanColdMountCandidates();
}
async function confirmMount(candidate) {
  if (await confirmColdVolumeMounted(mountVolume.value.id, candidate)) mountDialog.value = false;
}
async function prepareOffline(volume) {
  if (confirm(`确认将冷备卷“${volume.displayName}”准备离线？请等待系统确认后再执行操作系统安全弹出。`)) await prepareColdVolumeOffline(volume.id);
}
async function abandon(archive) {
  const reason = prompt('请输入放弃原因', '管理员确认放弃本次失败尝试');
  if (reason && await abandonStorageArchive(archive.id, reason)) { selectedStorageArchive.value = null; await loadColdStorage(); }
}
onMounted(async () => { await loadColdStorage(); refreshTimer = window.setInterval(() => { if (activeArchives.value.length) refresh(); }, 5000); });
onUnmounted(() => { if (refreshTimer) window.clearInterval(refreshTimer); });
</script>

<template>
  <div class="page-stack cold-storage-page">
    <section class="page-heading">
      <div><div class="eyebrow">OFFLINE MEDIA VAULT</div><h2>冷备管理</h2><p>管理整盘封存校验、离线介质定位和原冷备盘重新接入。这里的接入不会复制或移动订单文件。</p></div>
      <div class="heading-actions"><el-button :loading="coldStorageLoading" @click="refresh"><el-icon><Refresh /></el-icon>刷新状态</el-button><el-button @click="navigate('storage-volumes')">返回存储卷管理</el-button></div>
    </section>
    <section class="cold-metrics">
      <el-card shadow="never"><span>正在封存</span><strong>{{ activeArchives.length }}</strong><small>校验期间请勿拔盘</small></el-card>
      <el-card shadow="never"><span>需要处理</span><strong class="danger-value">{{ failedArchives.length }}</strong><small>失败或取消的封存尝试</small></el-card>
      <el-card shadow="never"><span>在线冷备介质</span><strong>{{ onlineCold.length }}</strong><small>可直接下载或线上恢复</small></el-card>
      <el-card shadow="never"><span>离线冷备介质</span><strong>{{ coldVolumes.length - onlineCold.length }}</strong><small>按标签与存放位置查找</small></el-card>
    </section>
    <el-card shadow="never" class="page-card">
      <template #header><div class="card-title"><span>封存任务</span><el-tag>{{ coldStorageArchives.length }} 次尝试</el-tag></div></template>
      <el-table v-loading="coldStorageLoading" :data="coldStorageArchives" empty-text="暂无整盘封存记录" stripe>
        <el-table-column label="封存卷" min-width="180"><template #default="{ row }"><strong>{{ row.sourceVolume?.displayName || row.archive.displayNameSnapshot || '未知卷' }}</strong><div class="table-muted">{{ row.sourceVolume?.volumeCode || row.archive.sourceVolumeId }}</div></template></el-table-column>
        <el-table-column label="阶段 / 进度" min-width="190"><template #default="{ row }"><el-tag :type="phaseType(row.archive.phase)" size="small">{{ row.archive.phase }}</el-tag><el-progress :percentage="progress(row)" :show-text="false" class="archive-progress" /></template></el-table-column>
        <el-table-column prop="archive.periodLabel" label="订单周期" width="190" />
        <el-table-column label="订单 / 文件" width="130"><template #default="{ row }">{{ row.archive.orderCount || 0 }} / {{ row.archive.managedFileCount || 0 }}</template></el-table-column>
        <el-table-column label="数据量" width="120"><template #default="{ row }">{{ bytes(row.archive.totalBytes) }}</template></el-table-column>
        <el-table-column prop="archive.archiveNote" label="封存备注" min-width="200" show-overflow-tooltip />
        <el-table-column prop="archive.failureMessage" label="失败原因" min-width="220" show-overflow-tooltip><template #default="{ row }"><span :class="{ 'danger-value': row.archive.failureMessage }">{{ row.archive.failureMessage || '—' }}</span></template></el-table-column>
        <el-table-column label="操作" width="150" fixed="right"><template #default="{ row }"><el-button link type="primary" @click="openColdStorageArchive(row.archive.id)">查看详情</el-button><el-button v-if="['PREPARE_FAILED','VERIFY_FAILED','COMMIT_FAILED','CANCELLED'].includes(row.archive.phase)" link @click="navigate('storage-volumes')">重新封存</el-button></template></el-table-column>
      </el-table>
    </el-card>
    <el-card shadow="never" class="page-card">
      <template #header><div class="card-title"><span>冷备介质</span><el-tag type="info">{{ coldVolumes.length }} 块</el-tag></div></template>
      <el-table :data="coldVolumes" empty-text="暂无已封存冷备介质" stripe>
        <el-table-column label="介质身份" min-width="180"><template #default="{ row }"><strong>{{ row.displayName }}</strong><div class="table-muted">{{ row.volumeCode }}</div></template></el-table-column>
        <el-table-column label="状态" width="150"><template #default="{ row }"><el-tag :type="row.mountStatus === 'ONLINE' ? 'success' : 'info'">{{ row.mountStatus }}</el-tag><div class="table-muted">{{ row.operationStatus || 'IDLE' }}</div></template></el-table-column>
        <el-table-column prop="periodLabel" label="订单周期" width="190"><template #default="{ row }">{{ row.orderCreatedFrom || '—' }} 至 {{ row.orderCreatedTo || '—' }}</template></el-table-column>
        <el-table-column label="订单 / 文件 / 容量" min-width="190"><template #default="{ row }">{{ row.archivedOrderCount || 0 }} / {{ row.archivedFileCount || 0 }} / {{ bytes(row.archivedBytes) }}</template></el-table-column>
        <el-table-column prop="offlineLabelNote" label="线下标签备注" min-width="180"><template #default="{ row }">{{ row.offlineLabelNote || '—' }}</template></el-table-column>
        <el-table-column prop="physicalLocation" label="存放位置" min-width="150"><template #default="{ row }">{{ row.physicalLocation || '—' }}</template></el-table-column>
        <el-table-column label="操作" width="190" fixed="right"><template #default="{ row }"><el-button v-if="row.mountStatus === 'ONLINE'" size="small" type="warning" plain @click="prepareOffline(row)">准备离线</el-button><el-button v-else size="small" type="primary" plain @click="openMount(row)">接入原盘</el-button></template></el-table-column>
      </el-table>
    </el-card>
    <VolumeArchiveDetail :detail="selectedStorageArchive" @close="selectedStorageArchive = null" @abandon="abandon" />
    <ColdVolumeMountDialog v-model="mountDialog" :volume="mountVolume" :storage-candidates="storageCandidates" :loading="coldStorageLoading" @scan="scanColdMountCandidates" @confirm-mounted="confirmMount" />
  </div>
</template>

<style scoped>
.cold-metrics { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 14px; }
.cold-metrics :deep(.el-card__body) { display: grid; gap: 6px; }
.cold-metrics span { color: var(--el-text-color-secondary); font-size: 13px; }
.cold-metrics strong { font-size: 30px; line-height: 1; }
.cold-metrics small { color: var(--el-text-color-secondary); }
.danger-value { color: var(--el-color-danger); }
.archive-progress { margin-top: 8px; }
@media (max-width: 1100px) { .cold-metrics { grid-template-columns: repeat(2, 1fr); } }
</style>
