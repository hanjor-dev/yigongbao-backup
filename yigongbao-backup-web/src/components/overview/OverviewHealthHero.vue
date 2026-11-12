<script setup>
import { computed } from 'vue';
import { elementResultIcon, formatTime, healthPresentation, orderedAlerts } from '../../overviewModel.js';

const props = defineProps({
  health: Object,
  observedAt: String,
  alerts: Array,
  error: Object,
  refreshing: Boolean
});
defineEmits(['navigate', 'refresh', 'dismiss']);

const state = computed(() => healthPresentation(props.health?.level));
const resultIcon = computed(() => elementResultIcon(state.value.tone));
const topAlert = computed(() => orderedAlerts(props.alerts)[0] || null);
const alertType = severity => severity === 'CRITICAL' ? 'error' : severity === 'WARNING' ? 'warning' : 'info';
const compactResultStyle = Object.freeze({
  '--el-result-padding':'12px 16px',
  '--el-result-icon-font-size':'48px',
  '--el-result-title-margin-top':'12px',
  '--el-result-subtitle-margin-top':'6px'
});
const alertCountValueStyle = Object.freeze({ fontSize:'32px', fontWeight:700 });
</script>

<template>
  <el-card shadow="never" aria-live="polite">
    <template #header>
      <el-row justify="space-between" align="middle">
        <el-text tag="strong" size="large">系统运行状态</el-text>
        <el-button circle :loading="refreshing" aria-label="刷新数据" title="刷新数据" @click="$emit('refresh')">
          <el-icon><Refresh /></el-icon>
        </el-button>
      </el-row>
    </template>

    <el-row :gutter="16" align="middle">
      <el-col :xs="24" :md="7">
        <el-result :icon="resultIcon" :title="state.label" :sub-title="`观测于 ${formatTime(observedAt)}`" :style="compactResultStyle" />
      </el-col>

      <el-col :xs="24" :md="11">
        <el-alert v-if="topAlert" :type="alertType(topAlert.severity)" :closable="false" show-icon>
          <template #title>{{ topAlert.title }}</template>
          <el-space direction="vertical" alignment="flex-start">
            <el-text>{{ topAlert.message }}</el-text>
            <el-text type="info" size="small">证据时间：{{ formatTime(topAlert.evidenceAt) }}</el-text>
            <el-space wrap>
              <el-button type="primary" plain size="small" @click="$emit('navigate', topAlert)">
                查看并处理
                <el-icon><ArrowRight /></el-icon>
              </el-button>
              <el-button type="danger" plain size="small" @click="$emit('dismiss', topAlert)">
                关闭告警
                <el-icon><Close /></el-icon>
              </el-button>
            </el-space>
          </el-space>
        </el-alert>
        <el-result v-else icon="success" title="当前无待处理告警" sub-title="关键备份与存储状态未发现异常" :style="compactResultStyle" />
      </el-col>

      <el-col :xs="24" :md="6">
        <el-statistic title="当前告警" :value="health?.activeAlertCount ?? 0" :value-style="alertCountValueStyle" />
        <el-divider />
        <el-space wrap>
          <el-tag type="danger">严重 {{ health?.criticalAlertCount ?? 0 }}</el-tag>
          <el-tag type="warning">警告 {{ health?.warningAlertCount ?? 0 }}</el-tag>
        </el-space>
      </el-col>
    </el-row>

    <el-alert v-if="error" type="warning" :closable="false" show-icon>
      <template #title>刷新失败，正在展示上次成功数据</template>
      {{ error.message }}<span v-if="error.code"> · {{ error.code }}</span><span v-if="error.requestId"> · requestId {{ error.requestId }}</span>
    </el-alert>
  </el-card>
</template>
