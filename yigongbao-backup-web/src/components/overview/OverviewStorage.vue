<script setup>
import { computed } from 'vue';
import { formatBytes, formatCount, formatPercent, formatTime, remainingCapacity } from '../../overviewModel.js';

const props = defineProps({ storage: Object });
defineEmits(['navigate']);

const remaining = computed(() => remainingCapacity(props.storage?.onlineCapacity?.totalBytes, props.storage?.onlineCapacity?.usedBytes));
</script>

<template>
  <el-card shadow="never">
    <template #header>
      <el-row justify="space-between" align="middle">
        <el-text tag="strong">存储态势</el-text>
        <el-button link type="primary" @click="$emit('navigate', { actionRoute: 'storage-volumes' })">管理存储卷</el-button>
      </el-row>
    </template>

    <el-space direction="vertical" fill :size="16">
      <el-row :gutter="16" align="middle">
        <el-col :span="12">
          <el-statistic title="在线存储使用率" :value="storage?.onlineCapacity?.usedPercent ?? 0" :formatter="() => formatPercent(storage?.onlineCapacity?.usedPercent)" />
        </el-col>
        <el-col :span="12">
          <el-statistic title="剩余容量" :value="remaining ?? 0" :formatter="() => remaining == null ? '—' : formatBytes(remaining)" />
        </el-col>
      </el-row>

      <el-progress
        v-if="storage?.onlineCapacity?.usedPercent != null"
        :percentage="Number(storage.onlineCapacity.usedPercent)"
        :stroke-width="12"
      />

      <el-descriptions :column="2" border size="small">
        <el-descriptions-item label="已用容量">{{ formatBytes(storage?.onlineCapacity?.usedBytes) }}</el-descriptions-item>
        <el-descriptions-item label="总容量">{{ formatBytes(storage?.onlineCapacity?.totalBytes) }}</el-descriptions-item>
        <el-descriptions-item label="活动热备卷">
          <el-space wrap>
            <el-text>{{ storage?.activeVolume?.name || '未配置' }}</el-text>
            <el-button link type="primary" @click="$emit('navigate', { actionRoute: 'storage-volumes', actionQuery: { volumeId: storage?.activeVolume?.id } })">查看</el-button>
          </el-space>
        </el-descriptions-item>
        <el-descriptions-item label="容量快照">{{ storage?.activeVolume?.capacityLabel || '容量不可用' }} · {{ formatTime(storage?.activeVolume?.capacityObservedAt) }}</el-descriptions-item>
        <el-descriptions-item label="就绪备用卷">{{ formatCount(storage?.standby?.readyCount) }}</el-descriptions-item>
        <el-descriptions-item label="首选备用卷">{{ storage?.standby?.preferred?.name || '暂无' }}</el-descriptions-item>
        <el-descriptions-item label="冷备介质">
          <el-space wrap>
            <el-text>{{ formatCount(storage?.coldMedia?.successfulArchiveCount) }}</el-text>
            <el-button link type="primary" @click="$emit('navigate', { actionRoute: 'cold-storage' })">查看</el-button>
          </el-space>
        </el-descriptions-item>
        <el-descriptions-item label="冷备状态">在线 {{ formatCount(storage?.coldMedia?.onlineCount) }} · 预期离线 {{ formatCount(storage?.coldMedia?.expectedOfflineCount) }} · 待修复 {{ formatCount(storage?.coldMedia?.repairRequiredCount) }}</el-descriptions-item>
      </el-descriptions>
    </el-space>
  </el-card>
</template>
