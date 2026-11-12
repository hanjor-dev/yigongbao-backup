<script setup>
import { computed } from 'vue';
import { formatTime, orderedAlerts } from '../../overviewModel.js';

const props = defineProps({ alerts: Array });
defineEmits(['navigate', 'dismiss']);

const items = computed(() => orderedAlerts(props.alerts).slice(1));
const tone = severity => severity === 'CRITICAL' ? 'danger' : severity === 'WARNING' ? 'warning' : 'info';
</script>

<template>
  <el-card shadow="never">
    <template #header>
      <el-row justify="space-between" align="middle">
        <el-text tag="strong">其他待处理事项</el-text>
        <el-tag type="warning">{{ items.length }} 项</el-tag>
      </el-row>
    </template>
    <el-empty v-if="!items.length" description="当前没有需要处理的告警" :image-size="72" />
    <el-table v-else :data="items" size="small" :max-height="360">
      <el-table-column label="级别" width="88">
        <template #default="{ row }">
          <el-tag size="small" :type="tone(row.severity)">{{ row.severity === 'CRITICAL' ? '严重' : '警告' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="title" label="事项" min-width="160" />
      <el-table-column prop="message" label="说明" min-width="280" show-overflow-tooltip />
      <el-table-column label="证据时间" width="180">
        <template #default="{ row }">{{ formatTime(row.evidenceAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="132" align="right">
        <template #default="{ row }">
          <el-space :size="4">
            <el-button link type="primary" @click="$emit('navigate', row)">处理</el-button>
            <el-button link type="danger" @click="$emit('dismiss', row)">关闭</el-button>
          </el-space>
        </template>
      </el-table-column>
    </el-table>
  </el-card>
</template>
