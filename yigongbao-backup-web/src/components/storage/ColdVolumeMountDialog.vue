<script setup>
import { computed, ref, watch } from 'vue';

const props = defineProps({
  modelValue: Boolean,
  volume: { type: Object, default: null },
  storageCandidates: { type: Array, default: () => [] },
  loading: Boolean,
});
const emit = defineEmits(['update:modelValue', 'scan', 'confirm-mounted']);
const selectedMountPath = ref('');
watch(() => props.modelValue, (visible) => { if (visible) selectedMountPath.value = ''; });
const selectedCandidate = computed(() => props.storageCandidates.find((candidate) => String(candidate.mountPath) === selectedMountPath.value));
const bytes = (value) => value == null ? '—' : value < 1073741824 ? `${(value / 1048576).toFixed(1)} MB` : `${(value / 1073741824).toFixed(2)} GB`;
</script>

<template>
  <el-dialog :model-value="modelValue" title="冷备盘接入并确认挂载" width="720px" destroy-on-close @update:model-value="(value) => emit('update:modelValue', value)">
    <el-alert type="info" :closable="false" show-icon>系统只接受扫描到的挂载点，并会在服务端核对卷 marker 与物理指纹；盘符相同不代表介质相同。</el-alert>
    <el-descriptions v-if="volume" :column="2" border class="mount-volume-summary">
      <el-descriptions-item label="待接入冷备卷">{{ volume.displayName }}（{{ volume.volumeCode }}）</el-descriptions-item>
      <el-descriptions-item label="登记状态">{{ volume.mountStatus }} / {{ volume.writeStatus }}</el-descriptions-item>
      <el-descriptions-item label="线下标签">{{ volume.offlineLabelNote || '—' }}</el-descriptions-item>
      <el-descriptions-item label="存放位置">{{ volume.physicalLocation || '—' }}</el-descriptions-item>
    </el-descriptions>
    <div class="mount-toolbar"><strong>扫描到的候选挂载点</strong><el-button :loading="loading" @click="emit('scan')"><el-icon><Refresh /></el-icon>重新扫描</el-button></div>
    <el-radio-group v-model="selectedMountPath" class="candidate-list">
      <el-radio v-for="candidate in storageCandidates" :key="String(candidate.mountPath)" :value="String(candidate.mountPath)" border>
        <span class="candidate-main">{{ candidate.mountPath }}</span>
        <span class="candidate-meta">{{ candidate.fileStoreName || candidate.fileStoreType || candidate.accessType }} · 可用 {{ bytes(candidate.usableBytes) }} / 共 {{ bytes(candidate.totalBytes) }}</span>
      </el-radio>
    </el-radio-group>
    <el-empty v-if="!storageCandidates.length" description="尚未扫描到可访问介质，请插盘后重新扫描" :image-size="72" />
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" data-action="confirm-mounted" :disabled="!selectedCandidate" @click="emit('confirm-mounted', selectedCandidate)">校验身份并确认挂载</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.mount-volume-summary { margin-top: 16px; }
.mount-toolbar { display: flex; align-items: center; justify-content: space-between; margin: 20px 0 12px; }
.candidate-list { display: grid; gap: 10px; width: 100%; }
.candidate-list :deep(.el-radio) { width: 100%; height: auto; min-height: 62px; margin: 0; padding: 12px 16px; }
.candidate-main, .candidate-meta { display: block; }
.candidate-main { font-weight: 700; }
.candidate-meta { margin-top: 5px; color: var(--el-text-color-secondary); font-size: 12px; }
</style>
