<script setup>
import { useBackupContext } from '../composables/useBackupContext.js';
const { overview, loadOverview } = useBackupContext();
</script>
<template>
  <div class="page-stack"><section class="page-heading"><div><div class="eyebrow">OPERATIONS</div><h2>管理概览</h2><p>实时掌握本地备份资产、存储空间与长任务执行状态。</p></div><el-button type="primary" plain @click="loadOverview"><el-icon><Refresh /></el-icon>刷新数据</el-button></section><el-row v-if="overview" :gutter="16"><el-col :xs="24" :sm="12" :lg="6"><metric-card label="订单快照" :value="overview.orderCount ?? overview.totalOrders ?? 0" detail="已纳入本地快照" /></el-col><el-col :xs="24" :sm="12" :lg="6"><metric-card label="关联文件" :value="overview.assetCount ?? overview.totalFiles ?? 0" detail="已建立资产关系" /></el-col><el-col :xs="24" :sm="12" :lg="6"><metric-card label="热备容量" :value="overview.totalBytes ?? overview.storageBytes ?? 0" detail="NAS 热备存储统计" /></el-col><el-col :xs="24" :sm="12" :lg="6"><metric-card label="运行任务" :value="overview.runningJobs ?? overview.activeJobs ?? 0" detail="持久化长任务" /></el-col></el-row><el-card v-if="overview" shadow="never" class="page-card"><template #header><div class="detail-header"><strong>系统运行摘要</strong><el-tag type="success" effect="plain">服务正常</el-tag></div></template><pre class="json-preview">{{ JSON.stringify(overview, null, 2) }}</pre></el-card><el-empty v-else description="暂无概览数据" />
  </div>
</template>
