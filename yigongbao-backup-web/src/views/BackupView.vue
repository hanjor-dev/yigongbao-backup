<script setup>
import JobTable from '../components/jobs/JobTable.vue';
import JobDetail from '../components/jobs/JobDetail.vue';
import { useBackupContext } from '../composables/useBackupContext.js';

const { loadJobs, runScheduledBackupNow, scheduledBackupRunning } = useBackupContext();
</script>

<template>
  <div class="page-stack">
    <section class="page-heading">
      <div><div class="eyebrow">AUTOMATIC BACKUP</div><h2>订单备份</h2><p>系统在每个计划时间自动汇总全部增量正式订单，并生成一个可追踪的备份任务。</p></div>
      <div class="toolbar-actions">
        <el-button :loading="scheduledBackupRunning" type="primary" @click="runScheduledBackupNow">立即执行扫描</el-button>
        <el-button plain @click="loadJobs">刷新任务</el-button>
      </div>
    </section>
    <el-alert title="错过当天配置的开始时间时不补跑，将等待下一个计划时间。管理员可使用“立即执行扫描”进行受控补偿；单订单任一文件失败则整单失败，但不阻断同批其他订单。" type="info" show-icon :closable="false" />
    <el-card shadow="never" class="table-card mb-16"><template #header>备份任务列表</template><JobTable /></el-card>
    <JobDetail />
  </div>
</template>
