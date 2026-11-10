<script setup>
import { computed, onMounted, ref } from 'vue';
import { useBackupContext } from '../composables/useBackupContext.js';

const { isAdmin, storageVolumes, storageCandidates, loadStorageVolumes, loadStorageVolumeDetail, scanStorageVolumes, registerStorageVolume, changeStorageVolumeState, previewStorageArchive, startStorageArchive, navigate } = useBackupContext();
const hotActive = computed(() => storageVolumes.value.find((item) => item.tier === 'HOT' && item.writeStatus === 'ACTIVE'));
const selectedVolume = ref(null);
const statusType = (value) =>
  ({
    ONLINE: 'success',
    OFFLINE: 'info',
    UNAVAILABLE: 'danger',
    ACTIVE: 'success',
    STANDBY: 'warning',
    READ_ONLY: 'info',
    DISABLED: 'danger',
  })[value] || 'info';
const bytes = (value) => (value == null ? '不可用' : value < 1048576 ? `${(value / 1024).toFixed(1)} KB` : value < 1073741824 ? `${(value / 1048576).toFixed(1)} MB` : `${(value / 1073741824).toFixed(2)} GB`);
async function activate(row) {
  if (confirm(`确认切换活动热备卷至“${row.displayName}”？原活动卷将转为只读。`)) await changeStorageVolumeState(row.id, 'set-active');
}
async function failover(row) {
  const target = standbyVolumes.value[0];
  if (!target) return alert('没有可接替的在线备用热备卷');
  if (confirm(`确认故障切换至“${target.displayName}”？原活动卷必须已经不可用。`))
    await changeStorageVolumeState(row.id, 'failover', {
      standbyVolumeId: target.id,
    });
}
const archiveDialog = ref(false);
const archiveSource = ref(null);
const archivePreview = ref(null);
const archiveForm = ref({
  successorVolumeId: null,
  archiveNote: '',
  offlineLabelNote: '',
  physicalLocation: '',
});
const standbyVolumes = computed(() => storageVolumes.value.filter((v) => v.tier === 'HOT' && v.mountStatus === 'ONLINE' && v.writeStatus === 'STANDBY'));
async function markCold(row) {
  archiveSource.value = row;
  archiveForm.value = {
    successorVolumeId: row.writeStatus === 'ACTIVE' ? standbyVolumes.value[0]?.id || null : null,
    archiveNote: '',
    offlineLabelNote: '',
    physicalLocation: '',
  };
  archivePreview.value = await previewStorageArchive(row.id, archiveForm.value.successorVolumeId);
  archiveDialog.value = true;
}
async function refreshArchivePreview() {
  archivePreview.value = await previewStorageArchive(archiveSource.value.id, archiveForm.value.successorVolumeId);
}
async function submitArchive() {
  if (!archiveForm.value.archiveNote.trim()) return;
  const job = await startStorageArchive(archiveSource.value.id, {
    ...archiveForm.value,
    idempotencyKey: crypto.randomUUID(),
  });
  if (job) archiveDialog.value = false;
}
async function confirmMountedAt(row) {
  if (row.tier === 'COLD') { navigate('cold-storage'); return; }
  const mountPath = prompt('请输入当前扫描到的物理盘挂载点（盘符变化时必须填写）', row.rootPath?.replace(/[\\/]yigongbao-backup$/, '') || '');
  if (mountPath) await changeStorageVolumeState(row.id, 'confirm-mounted', { mountPath });
}
async function register(candidate) {
  const name = prompt('请输入存储卷名称', candidate.mountPath);
  if (name) await registerStorageVolume(candidate, name);
}
async function operate(row, action, label) {
  if (confirm(`确认${label}“${row.displayName}”？系统会检查运行任务和卷状态。`)) await changeStorageVolumeState(row.id, action);
}
async function openDetail(row) {
  selectedVolume.value = await loadStorageVolumeDetail(row.id);
}
onMounted(loadStorageVolumes);
</script>

<template>
  <div class="page-stack">
    <section class="page-heading">
      <div>
        <div class="eyebrow">STORAGE VOLUMES</div>
        <h2>存储卷管理</h2>
        <p>统一管理 Windows 本地盘、映射 NAS 与离线冷备盘。文件位置由存储卷台账决定，不再通过配置填写路径。</p>
      </div>
      <div class="heading-actions" v-if="isAdmin">
        <el-button @click="navigate('cold-storage')"><el-icon><Box /></el-icon>冷备管理</el-button>
        <el-button @click="loadStorageVolumes"
          ><el-icon><Refresh /></el-icon>刷新</el-button
        ><el-button type="primary" @click="scanStorageVolumes"
          ><el-icon><Search /></el-icon>扫描可用卷</el-button
        >
      </div>
    </section>
    <el-alert v-if="hotActive" type="success" :closable="false" show-icon>当前活动热备卷：{{ hotActive.displayName }}（{{ hotActive.volumeCode }}）</el-alert>
    <el-alert v-else type="warning" :closable="false" show-icon>尚未配置活动热备卷；新的备份与补传任务会被安全拒绝。</el-alert>
    <el-card shadow="never" class="page-card"
      ><template #header
        ><div class="card-title">
          <span>已纳管存储卷</span><el-tag>{{ storageVolumes.length }} 个</el-tag>
        </div></template
      >
      <el-table :data="storageVolumes" empty-text="暂无已纳管存储卷，请先扫描并纳管。" stripe>
        <el-table-column prop="displayName" label="存储卷" min-width="170"
          ><template #default="{ row }"
            ><strong>{{ row.displayName }}</strong>
            <div class="table-muted">{{ row.volumeCode }}</div></template
          ></el-table-column
        >
        <el-table-column prop="tier" label="分层" width="95"
          ><template #default="{ row }"
            ><el-tag size="small">{{ row.tier === 'HOT' ? '热备' : row.tier === 'COLD' ? '冷备' : '导入区' }}</el-tag></template
          ></el-table-column
        >
        <el-table-column prop="mountStatus" label="挂载状态" width="120"
          ><template #default="{ row }"
            ><el-tag :type="statusType(row.mountStatus)" size="small">{{ row.mountStatus }}</el-tag></template
          ></el-table-column
        >
        <el-table-column prop="writeStatus" label="写入状态" width="120"
          ><template #default="{ row }"
            ><el-tag :type="statusType(row.writeStatus)" size="small">{{ row.writeStatus }}</el-tag>
            <div class="table-muted">
              {{ row.operationStatus || 'IDLE' }}
            </div></template
          ></el-table-column
        >
        <el-table-column prop="priority" label="优先级" width="90" />
        <el-table-column label="容量（已用 / 可用 / 总）" min-width="230"
          ><template #default="{ row }">{{ bytes(row.usedBytes) }} / {{ bytes(row.usableBytes) }} / {{ bytes(row.totalBytes) }}</template></el-table-column
        >
        <el-table-column prop="lastErrorMessage" label="最近异常" min-width="180"
          ><template #default="{ row }">{{ row.lastErrorMessage || '—' }}</template></el-table-column
        >
        <el-table-column label="详情" width="90"
          ><template #default="{ row }"><el-button size="small" link @click="openDetail(row)">卷详情</el-button></template></el-table-column
        >
        <el-table-column v-if="isAdmin" label="操作" width="460" fixed="right"
          ><template #default="{ row }"
            ><el-button size="small" @click="operate(row, 'probe', '重新探测')">重新探测</el-button><el-button v-if="row.tier === 'HOT' && row.mountStatus === 'ONLINE' && row.writeStatus !== 'ACTIVE'" size="small" type="primary" plain @click="activate(row)">设为活动卷</el-button><el-button v-if="row.tier === 'HOT' && row.writeStatus === 'ACTIVE' && row.mountStatus !== 'ONLINE'" size="small" type="danger" plain @click="failover(row)">故障切换</el-button><el-button v-if="row.mountStatus !== 'ONLINE'" size="small" @click="confirmMountedAt(row)">确认挂载</el-button><el-button v-if="row.tier === 'HOT' && row.writeStatus !== 'ACTIVE'" size="small" @click="operate(row, 'set-standby', '设为备用卷')">设为备用</el-button><el-button v-if="row.tier === 'HOT' && row.mountStatus === 'ONLINE'" size="small" @click="markCold(row)">整盘封存</el-button
            ><el-button v-if="row.tier === 'COLD' && row.mountStatus === 'ONLINE'" size="small" type="warning" plain @click="operate(row, 'prepare-offline', '准备离线')">准备离线</el-button></template
          ></el-table-column
        >
      </el-table>
    </el-card>
    <el-card v-if="storageCandidates.length" shadow="never" class="page-card"
      ><template #header
        ><div class="card-title"><span>扫描到的候选卷</span><el-tag type="info">仅发现，尚未纳管</el-tag></div></template
      >
      <el-table :data="storageCandidates" stripe
        ><el-table-column prop="mountPath" label="挂载点" min-width="220" /><el-table-column prop="rootPath" label="受控目录" min-width="280" /><el-table-column prop="accessType" label="访问类型" width="120" /><el-table-column label="容量（已用 / 可用 / 总）" min-width="230"
          ><template #default="{ row: candidate }">{{ bytes(candidate.usedBytes) }} / {{ bytes(candidate.usableBytes) }} / {{ bytes(candidate.totalBytes) }}</template></el-table-column
        ><el-table-column v-if="isAdmin" label="操作" width="100"
          ><template #default="{ row }"><el-button size="small" type="primary" @click="register(row)">纳管</el-button></template></el-table-column
        ></el-table
      >
    </el-card>
    <el-drawer v-model="selectedVolume" title="存储卷详情" size="520px"
      ><template v-if="selectedVolume"
        ><el-descriptions :column="1" border
          ><el-descriptions-item label="卷代码">{{ selectedVolume.volume?.volumeCode }}</el-descriptions-item
          ><el-descriptions-item label="受控路径">{{ selectedVolume.volume?.rootPath }}</el-descriptions-item
          ><el-descriptions-item label="容量（已用 / 可用 / 总）">{{ bytes(selectedVolume.volume?.usedBytes) }} / {{ bytes(selectedVolume.volume?.usableBytes) }} / {{ bytes(selectedVolume.volume?.totalBytes) }}</el-descriptions-item
          ><el-descriptions-item label="挂载/写入/操作">{{ selectedVolume.volume?.mountStatus }} / {{ selectedVolume.volume?.writeStatus }} / {{ selectedVolume.volume?.operationStatus || 'IDLE' }}</el-descriptions-item
          ><el-descriptions-item label="封存备注">{{ selectedVolume.volume?.archiveNote || '—' }}</el-descriptions-item
          ><el-descriptions-item label="线下标签 / 存放位置">{{ selectedVolume.volume?.offlineLabelNote || '—' }} / {{ selectedVolume.volume?.physicalLocation || '—' }}</el-descriptions-item
          ><el-descriptions-item label="订单创建周期">{{ selectedVolume.volume?.orderCreatedFrom || '—' }} 至 {{ selectedVolume.volume?.orderCreatedTo || '—' }}</el-descriptions-item
          ><el-descriptions-item label="封存统计">{{ selectedVolume.volume?.archivedOrderCount || 0 }} 单 / {{ selectedVolume.volume?.archivedFileCount || 0 }} 文件 / {{ bytes(selectedVolume.volume?.archivedBytes) }}</el-descriptions-item
          ><el-descriptions-item label="卷标记 markerJson"
            ><code>{{ selectedVolume.markerJson || '—' }}</code></el-descriptions-item
          ><el-descriptions-item label="活跃副本 activeCopyCount">{{ selectedVolume.activeCopyCount }}</el-descriptions-item
          ><el-descriptions-item label="运行任务 runningTaskCount">{{ selectedVolume.runningTaskCount }}</el-descriptions-item
          ><el-descriptions-item label="最近错误">{{ selectedVolume.volume?.lastErrorMessage || '无' }}</el-descriptions-item></el-descriptions
        >
        <h4>最近审计 recentAudits</h4>
        <el-empty v-if="!selectedVolume.recentAudits?.length" description="暂无审计记录" :image-size="60" /><el-timeline v-else
          ><el-timeline-item v-for="audit in selectedVolume.recentAudits" :key="audit.id" :timestamp="audit.occurredAt"
            ><strong>{{ audit.action }}</strong> · {{ audit.resultCode }}
            <div v-if="audit.failureReason" class="table-muted">
              {{ audit.failureReason }}
            </div></el-timeline-item
          ></el-timeline
        ></template
      ></el-drawer
    >
    <el-dialog v-model="archiveDialog" title="整盘转为冷备" width="620px" destroy-on-close
      ><el-alert type="warning" :closable="false" show-icon>封存会校验整盘受管文件，不复制、不移动、不删除订单数据。任务成功后才可准备离线并拔盘。</el-alert
      ><el-form label-position="top" class="mt-16"
        ><el-form-item v-if="archiveSource?.writeStatus === 'ACTIVE'" label="接替活动热备卷（必选）"
          ><el-select v-model="archiveForm.successorVolumeId" style="width: 100%" @change="refreshArchivePreview"><el-option v-for="item in standbyVolumes" :key="item.id" :label="`${item.displayName} · ${item.volumeCode}`" :value="item.id" /></el-select></el-form-item
        ><el-form-item label="封存备注（必填）"><el-input v-model="archiveForm.archiveNote" maxlength="500" show-word-limit placeholder="例如：2026 年一季度订单原盘冷备" /></el-form-item><el-form-item label="线下标签备注"><el-input v-model="archiveForm.offlineLabelNote" placeholder="管理员可据此制作和粘贴物理标签" /></el-form-item><el-form-item label="线下存放位置"><el-input v-model="archiveForm.physicalLocation" placeholder="例如：档案柜 A-03" /></el-form-item></el-form
      ><el-descriptions v-if="archivePreview" :column="2" border
        ><el-descriptions-item label="订单周期" :span="2">{{ archivePreview.periodLabel || '—' }}</el-descriptions-item
        ><el-descriptions-item label="订单数">{{ archivePreview.orderCount }}</el-descriptions-item
        ><el-descriptions-item label="文件数">{{ archivePreview.managedFileCount }}</el-descriptions-item
        ><el-descriptions-item label="数据量" :span="2">{{ bytes(archivePreview.totalBytes) }}</el-descriptions-item
        ><el-descriptions-item label="阻塞项" :span="2"
          ><span v-if="!archivePreview.blockers?.length">无</span><el-tag v-for="item in archivePreview.blockers" :key="item.code" type="danger" class="mr-8">{{ item.message }}</el-tag></el-descriptions-item
        ></el-descriptions
      ><template #footer><el-button @click="archiveDialog = false">取消</el-button><el-button type="primary" :disabled="!archiveForm.archiveNote.trim() || archivePreview?.blockers?.length" @click="submitArchive">确认并启动校验</el-button></template></el-dialog
    >
  </div>
</template>
