<script setup>
import { computed, onMounted, ref } from 'vue';
import { useBackupContext } from '../composables/useBackupContext.js';

const { isAdmin, storageVolumes, storageCandidates, loadStorageVolumes, scanStorageVolumes, registerStorageVolume, changeStorageVolumeState } = useBackupContext();
const hotActive = computed(() => storageVolumes.value.find(item => item.tier === 'HOT' && item.writeStatus === 'ACTIVE'));
const selectedVolume = ref(null);
const statusType = (value) => ({ ONLINE: 'success', OFFLINE: 'info', UNAVAILABLE: 'danger', ACTIVE: 'success', STANDBY: 'warning', READ_ONLY: 'info', DISABLED: 'danger' }[value] || 'info');
const bytes = (value) => value == null ? '不可用' : value < 1048576 ? `${(value / 1024).toFixed(1)} KB` : value < 1073741824 ? `${(value / 1048576).toFixed(1)} MB` : `${(value / 1073741824).toFixed(2)} GB`;
async function activate(row) { if (confirm(`确认切换活动热备卷至“${row.displayName}”？原活动卷将转为只读。`)) await changeStorageVolumeState(row.id, 'set-active'); }
async function markCold(row) { if (confirm(`确认将“${row.displayName}”转为冷备只读卷？`)) await changeStorageVolumeState(row.id, 'to-cold'); }
async function register(candidate) { const name = prompt('请输入存储卷名称', candidate.mountPath); if (name) await registerStorageVolume(candidate, name); }
async function operate(row, action, label) { if (confirm(`确认${label}“${row.displayName}”？系统会检查运行任务和卷状态。`)) await changeStorageVolumeState(row.id, action); }
onMounted(loadStorageVolumes);
</script>

<template>
  <div class="page-stack">
    <section class="page-heading">
      <div><div class="eyebrow">STORAGE VOLUMES</div><h2>存储卷管理</h2><p>统一管理 Windows 本地盘、映射 NAS 与离线冷备盘。文件位置由存储卷台账决定，不再通过配置填写路径。</p></div>
      <div class="heading-actions" v-if="isAdmin"><el-button @click="loadStorageVolumes"><el-icon><Refresh /></el-icon>刷新</el-button><el-button type="primary" @click="scanStorageVolumes"><el-icon><Search /></el-icon>扫描可用卷</el-button></div>
    </section>
    <el-alert v-if="hotActive" type="success" :closable="false" show-icon>当前活动热备卷：{{ hotActive.displayName }}（{{ hotActive.volumeCode }}）</el-alert>
    <el-alert v-else type="warning" :closable="false" show-icon>尚未配置活动热备卷；新的备份与补传任务会被安全拒绝。</el-alert>
    <el-card shadow="never" class="page-card"><template #header><div class="card-title"><span>已纳管存储卷</span><el-tag>{{ storageVolumes.length }} 个</el-tag></div></template>
      <el-table :data="storageVolumes" empty-text="暂无已纳管存储卷，请先扫描并纳管。" stripe>
        <el-table-column prop="displayName" label="存储卷" min-width="170"><template #default="{ row }"><strong>{{ row.displayName }}</strong><div class="table-muted">{{ row.volumeCode }}</div></template></el-table-column>
        <el-table-column prop="tier" label="分层" width="95"><template #default="{ row }"><el-tag size="small">{{ row.tier === 'HOT' ? '热备' : row.tier === 'COLD' ? '冷备' : '导入区' }}</el-tag></template></el-table-column>
        <el-table-column prop="mountStatus" label="挂载状态" width="120"><template #default="{ row }"><el-tag :type="statusType(row.mountStatus)" size="small">{{ row.mountStatus }}</el-tag></template></el-table-column>
        <el-table-column prop="writeStatus" label="写入状态" width="120"><template #default="{ row }"><el-tag :type="statusType(row.writeStatus)" size="small">{{ row.writeStatus }}</el-tag></template></el-table-column>
        <el-table-column prop="priority" label="优先级" width="90" />
        <el-table-column label="容量（已用 / 可用 / 总）" min-width="230"><template #default="{ row }">{{ bytes(row.usedBytes) }} / {{ bytes(row.usableBytes) }} / {{ bytes(row.totalBytes) }}</template></el-table-column>
        <el-table-column prop="lastErrorMessage" label="最近异常" min-width="180"><template #default="{ row }">{{ row.lastErrorMessage || '—' }}</template></el-table-column>
        <el-table-column label="详情" width="90"><template #default="{ row }"><el-button size="small" link @click="selectedVolume = row">卷详情</el-button></template></el-table-column>
        <el-table-column v-if="isAdmin" label="操作" width="390" fixed="right"><template #default="{ row }"><el-button size="small" @click="operate(row, 'probe', '重新探测')">重新探测</el-button><el-button v-if="row.tier === 'HOT' && row.mountStatus === 'ONLINE' && row.writeStatus !== 'ACTIVE'" size="small" type="primary" plain @click="activate(row)">设为活动卷</el-button><el-button v-if="row.mountStatus !== 'ONLINE'" size="small" @click="operate(row, 'confirm-mounted', '确认已挂载')">确认挂载</el-button><el-button v-if="row.tier === 'HOT' && row.writeStatus !== 'ACTIVE'" size="small" @click="operate(row, 'set-standby', '设为备用卷')">设为备用</el-button><el-button v-if="row.tier === 'HOT'" size="small" @click="markCold(row)">转冷备</el-button><el-button v-if="row.tier === 'COLD'" size="small" @click="operate(row, 'to-hot', '转为热备只读卷')">转热备</el-button><el-button v-if="row.writeStatus !== 'DISABLED'" size="small" type="danger" link @click="operate(row, 'disable', '停用')">停用</el-button></template></el-table-column>
      </el-table>
    </el-card>
    <el-card v-if="storageCandidates.length" shadow="never" class="page-card"><template #header><div class="card-title"><span>扫描到的候选卷</span><el-tag type="info">仅发现，尚未纳管</el-tag></div></template>
      <el-table :data="storageCandidates" stripe><el-table-column prop="mountPath" label="挂载点" min-width="220"/><el-table-column prop="rootPath" label="受控目录" min-width="280"/><el-table-column prop="accessType" label="访问类型" width="120"/><el-table-column prop="totalBytes" label="总容量" width="140"/><el-table-column v-if="isAdmin" label="操作" width="100"><template #default="{ row }"><el-button size="small" type="primary" @click="register(row)">纳管</el-button></template></el-table-column></el-table>
    </el-card>
    <el-drawer v-model="selectedVolume" title="存储卷详情" size="440px"><el-descriptions v-if="selectedVolume" :column="1" border><el-descriptions-item label="卷代码">{{ selectedVolume.volumeCode }}</el-descriptions-item><el-descriptions-item label="受控路径">{{ selectedVolume.rootPath }}</el-descriptions-item><el-descriptions-item label="容量（已用 / 可用 / 总）">{{ bytes(selectedVolume.usedBytes) }} / {{ bytes(selectedVolume.usableBytes) }} / {{ bytes(selectedVolume.totalBytes) }}</el-descriptions-item><el-descriptions-item label="挂载/写入">{{ selectedVolume.mountStatus }} / {{ selectedVolume.writeStatus }}</el-descriptions-item><el-descriptions-item label="最近探测">{{ selectedVolume.lastProbedAt || '未探测' }}</el-descriptions-item><el-descriptions-item label="运行任务">后端状态转换时会校验关联运行任务</el-descriptions-item><el-descriptions-item label="最近错误">{{ selectedVolume.lastErrorMessage || '无' }}</el-descriptions-item></el-descriptions></el-drawer>
  </div>
</template>
