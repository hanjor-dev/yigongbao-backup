<script setup>
import { useBackupContext } from '../composables/useBackupContext.js';
import JobTable from '../components/jobs/JobTable.vue';
import JobDetail from '../components/jobs/JobDetail.vue';
const { loadJobs } = useBackupContext();
</script>
<template>
  <section class="page-card"><div class="page-heading"><div><span class="eyebrow">LONG-RUNNING TASKS</span><h2>任务中心</h2><p>查看备份、恢复与清理任务的持久化进度和异常明细。</p></div><el-button type="primary" plain @click="loadJobs">刷新任务</el-button></div><el-card shadow="never" class="table-card mb-16"><template #header>任务列表</template><JobTable /></el-card><JobDetail /></section>
</template>
