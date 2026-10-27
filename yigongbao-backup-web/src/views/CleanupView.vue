<script setup>
import { useBackupContext } from '../composables/useBackupContext.js';
const { days, cleanupResult, dryCleanup, executeCleanup } = useBackupContext();
</script>

<template>
  <section class="card page-card"><div class="toolbar"><div><div class="eyebrow">CLOUD RETENTION</div><h2>COS 清理预览</h2></div></div><p class="muted">按订单创建时间计算保留周期；系统先生成预览，再由管理员确认执行。</p><div class="inline-form"><label>清理 {{days}} 天以前<input v-model.number="days" type="number" min="1" /></label><button class="primary" @click="dryCleanup">生成预览</button><button v-if="cleanupResult?.taskId" @click="executeCleanup">确认执行</button></div><p v-if="cleanupResult?.jobId && !cleanupResult?.taskId" class="warning">清理预览任务正在执行，请到任务中心等待试运行结果完成后再确认。</p><pre v-if="cleanupResult" class="json-preview">{{JSON.stringify(cleanupResult, null, 2)}}</pre><empty-state v-else message="尚未生成清理预览" /></section>
</template>
