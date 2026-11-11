<script setup>
import { statusLabel, workflowLabel } from '../StatusBadge.js';

defineProps({
  records: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false },
  current: { type: Number, default: 1 },
  size: { type: Number, default: 50 },
  total: { type: Number, default: 0 },
});

const emit = defineEmits(['page-change', 'size-change']);
const logTone = (level) => ({ ERROR: 'is-error', WARN: 'is-warn', INFO: 'is-info' })[String(level || '').toUpperCase()] || 'is-info';
const formatTime = (value) => value ? String(value).replace('T', ' ').replace(/\.\d+$/, '') : '-';
const detail = (value) => {
  try {
    return JSON.stringify(typeof value === 'string' ? JSON.parse(value) : (value || {}), null, 2);
  } catch {
    return value || '';
  }
};
const changePage = (value) => emit('page-change', value);
const changeSize = (value) => emit('size-change', value);
</script>

<template>
  <div class="execution-log-panel">
    <div v-loading="loading" class="log-console">
      <div v-if="!records.length" class="console-empty">暂无已持久化的执行日志。历史任务不会补写旧日志。</div>
      <details v-for="entry in records" :key="entry.id" class="console-entry" :class="logTone(entry.logLevel)">
        <summary>
          <time>{{ formatTime(entry.occurredAt) }}</time>
          <b>[{{ statusLabel(entry.logLevel || 'INFO') }}]</b>
          <span>[{{ workflowLabel(entry.stage || '-') }}]</span>
          <span>{{ entry.message }}</span>
        </summary>
        <pre v-if="entry.detailJson">{{ detail(entry.detailJson) }}</pre>
      </details>
    </div>
    <el-pagination
      v-if="total > 0"
      class="table-pagination"
      small
      background
      layout="total, sizes, prev, pager, next"
      :current-page="current"
      :page-size="size"
      :total="total"
      :page-sizes="[20, 50, 100]"
      @current-change="changePage"
      @size-change="changeSize"
    />
  </div>
</template>

<style scoped>
.log-console { min-height: 320px; max-height: calc(94vh - 360px); overflow: auto; padding: 14px 16px; border-radius: 10px; background: #111827; color: #d1d5db; font: 12px/1.7 ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; }
.console-entry { border-bottom: 1px solid #273244; padding: 8px 0; }
.console-entry summary { cursor: pointer; display: flex; gap: 8px; align-items: baseline; }
.console-entry time { color: #94a3b8; white-space: nowrap; }
.console-entry b { color: #60a5fa; }
.console-entry.is-warn b { color: #fbbf24; }
.console-entry.is-error b { color: #f87171; }
.console-entry span:last-child { color: #e5e7eb; overflow-wrap: anywhere; }
.console-entry pre { margin: 8px 0 0 20px; padding: 8px; white-space: pre-wrap; overflow-wrap: anywhere; color: #a5b4fc; background: #0b1120; border-radius: 4px; }
.console-empty { color: #94a3b8; padding: 48px 0; text-align: center; }
</style>
