<script setup>
import { useBackupContext } from '../composables/useBackupContext.js';
const { overview, loadOverview } = useBackupContext();
</script>

<template>
  <section class="card page-card">
    <div class="toolbar"><div><div class="eyebrow">OPERATIONS</div><h2>管理概览</h2></div><button @click="loadOverview">刷新</button></div>
    <div v-if="overview" class="overview-grid">
      <metric-card label="订单数" :value="overview.orderCount ?? overview.totalOrders ?? 0" detail="已纳入本地快照" />
      <metric-card label="文件数" :value="overview.assetCount ?? overview.totalFiles ?? 0" detail="关联文件对象" />
      <metric-card label="文件容量" :value="overview.totalBytes ?? overview.storageBytes ?? 0" detail="热备存储统计" />
      <metric-card label="运行任务" :value="overview.runningJobs ?? overview.activeJobs ?? 0" detail="长任务执行中" />
    </div>
    <pre v-if="overview" class="json-preview">{{ JSON.stringify(overview, null, 2) }}</pre>
    <empty-state v-else message="暂无概览数据" />
  </section>
</template>
