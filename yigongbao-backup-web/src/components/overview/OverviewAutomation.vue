<script setup>
import { computed } from 'vue';
import { statusLabel } from '../StatusBadge.js';
import { automationRows, elementTagType, executionModeLabel, formatInterval, formatTime } from '../../overviewModel.js';

const props = defineProps({ automation: Object });
defineEmits(['navigate']);

const rows = computed(() => automationRows(props.automation));
const schedulePeriodText = item => [
  item.executionMode ? executionModeLabel(item.executionMode) : '',
  item.schedule ? `计划 ${item.schedule}` : '',
  item.intervalValue ? formatInterval(item.intervalValue, item.intervalUnit) : ''
].filter(Boolean).join(' · ') || '未配置';
</script>

<template>
  <el-card shadow="never">
    <template #header><el-text tag="strong">自动化状态</el-text></template>
    <el-table :data="rows" size="small">
      <el-table-column prop="label" label="任务" width="96" />
      <el-table-column label="调度状态" width="108">
        <template #default="{ row }">
          <el-tag size="small" :type="elementTagType(row.scheduleStatus)">{{ statusLabel(row.scheduleStatus) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="最近一次执行状态" width="142">
        <template #default="{ row }">
          <el-tag size="small" :type="elementTagType(row.lastRunStatus)">{{ statusLabel(row.lastRunStatus) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="任务说明" min-width="220">
        <template #default="{ row }">
          <el-space direction="vertical" alignment="flex-start" :size="2">
            <el-text size="small">{{ row.message || row.note }}</el-text>
            <el-text v-if="row.lastError" type="danger" size="small">最近错误：{{ row.lastError }}</el-text>
          </el-space>
        </template>
      </el-table-column>
      <el-table-column label="调度时间" min-width="300">
        <template #default="{ row }">
          <el-space direction="vertical" alignment="flex-start" :size="2">
            <el-text size="small">周期：{{ schedulePeriodText(row) }}</el-text>
            <el-text type="info" size="small">最近成功：{{ formatTime(row.lastSuccessAt) }}</el-text>
            <el-text type="info" size="small">下次预期：{{ formatTime(row.nextExpectedAt) }}</el-text>
          </el-space>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="64" align="right">
        <template #default="{ row }"><el-button link type="primary" @click="$emit('navigate', row)">查看</el-button></template>
      </el-table-column>
    </el-table>
  </el-card>
</template>
