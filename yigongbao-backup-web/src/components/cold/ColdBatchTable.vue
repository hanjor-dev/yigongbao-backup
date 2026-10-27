<script setup>
import { useBackupContext } from '../../composables/useBackupContext.js';
const { coldBatches, selectedColdBatch, coldBusy, selectColdBatch, verifyColdBatch } = useBackupContext();
</script>
<template>
  <div class="table-wrap"><table><thead><tr><th>批次</th><th>冷备状态</th><th>热备清理</th><th>时间范围</th><th>订单/文件/容量</th><th>操作</th></tr></thead><tbody><tr v-for="b in coldBatches" :key="b.id" :class="{selected:selectedColdBatch?.id===b.id}" @click="selectColdBatch(b)"><td>{{b.batchNo}}</td><td><status-badge :value="b.status" /></td><td>{{b.hotCleanupStatus || '-'}}</td><td>{{b.orderTimeFrom || '-'}}<br />至 {{b.orderTimeTo || '-'}}</td><td>{{b.orderCount || 0}} 单 / {{b.artifactCount || 0}} 文件 / {{b.totalSize || 0}} bytes</td><td><button v-if="b.status==='COMPLETED'" @click.stop="verifyColdBatch(b)" :disabled="coldBusy">校验</button><button v-if="b.status==='VERIFIED'" @click.stop="selectColdBatch(b)">选择清理</button></td></tr></tbody></table><empty-state v-if="!coldBatches.length" message="暂无冷备批次" /></div>
</template>
