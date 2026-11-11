<script setup>
import { computed } from 'vue';
import { formatTime, orderedAlerts } from '../../overviewModel.js';
const props = defineProps({ alerts: Array });
defineEmits(['navigate']);
const items = computed(() => orderedAlerts(props.alerts).slice(0, 3));
const tone = severity => severity === 'CRITICAL' ? 'danger' : severity === 'WARNING' ? 'warning' : 'info';
</script>
<template><el-card shadow="never" class="overview-panel overview-alerts"><template #header><div class="overview-panel__title"><div><span>待处理事项</span><small>按严重程度与证据时间排序</small></div><el-tag :type="items.length ? 'warning' : 'success'">{{ items.length ? `${items.length} 项优先处理` : '暂无告警' }}</el-tag></div></template>
  <el-empty v-if="!items.length" description="当前没有需要处理的告警" :image-size="72" />
  <button v-for="item in items" :key="item.alertKey" class="overview-alert-row" type="button" @click="$emit('navigate', item)"><span class="overview-alert-row__rail" :class="`is-${tone(item.severity)}`"></span><span class="overview-alert-row__body"><span><el-tag size="small" :type="tone(item.severity)">{{ item.severity === 'CRITICAL' ? '严重' : '警告' }}</el-tag><strong>{{ item.title }}</strong></span><small>{{ item.message }}</small><em>{{ formatTime(item.evidenceAt) }}</em></span><el-icon><ArrowRight /></el-icon></button>
</el-card></template>
