<script setup>
import { useBackupContext } from '../composables/useBackupContext.js';
import JobTable from '../components/jobs/JobTable.vue';
import JobDetail from '../components/jobs/JobDetail.vue';
const { loadJobs } = useBackupContext();
</script>
<template>
  <section class="card page-card"><div class="toolbar"><div><div class="eyebrow">LONG-RUNNING TASKS</div><h2>任务中心</h2></div><button @click="loadJobs">刷新</button></div><JobTable /><JobDetail /></section>
</template>
