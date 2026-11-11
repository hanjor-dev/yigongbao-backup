<script setup>
import { formatBytes, formatCount } from '../../overviewModel.js';
defineProps({ summary: Object });
defineEmits(['navigate']);
</script>
<template><section class="overview-metrics">
  <button class="overview-metric" type="button" @click="$emit('navigate', { actionRoute: 'orders', actionQuery: { backupMode: 'REAL', storageAccessible: 'true' } })"><span>当前可读订单</span><strong>{{ formatCount(summary?.readableOrders?.total) }}</strong><div><b>{{ formatCount(summary?.readableOrders?.complete) }} 完整</b><b class="is-warning">{{ formatCount(summary?.readableOrders?.incomplete) }} 降级</b></div><small>{{ formatCount(summary?.readableOrders?.temporarilyOffline) }} 单仅在正常离线冷备中 · {{ formatCount(summary?.readableOrders?.degradedRedundancy) }} 单冗余降级</small></button>
  <article class="overview-metric"><span>本地受管文件</span><strong>{{ formatCount(summary?.readableFiles?.logicalFileCount) }}</strong><div><b>{{ formatBytes(summary?.readableFiles?.managedArtifactBytes) }} 物理存储</b></div><small>{{ formatCount(summary?.readableFiles?.unknownFileCountPackageCount) }} 个包文件数未知 · {{ formatCount(summary?.readableFiles?.unknownSizeCopyCount) }} 个副本大小未知 · {{ formatCount(summary?.readableFiles?.invalidCopyMetadataCount) }} 个副本元数据不全</small></article>
  <article class="overview-metric"><span>今日结束任务中的订单结果</span><strong>{{ formatCount(summary?.todayBackup?.total) }}</strong><div><b>{{ formatCount(summary?.todayBackup?.completeSucceeded) }} 完整成功</b><b class="is-warning">{{ formatCount(summary?.todayBackup?.incompleteSucceeded) }} 降级成功</b><b class="is-danger">{{ formatCount(summary?.todayBackup?.failed) }} 失败</b></div><small>另有 {{ formatCount(summary?.todayBackup?.skipped) }} 单跳过</small></article>
</section></template>
