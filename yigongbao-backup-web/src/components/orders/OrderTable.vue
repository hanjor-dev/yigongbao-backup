<script setup>
import { useBackupContext } from '../../composables/useBackupContext.js';
const { orders, selectedOrder, selectedIds, selectOrder, toggleSelected } = useBackupContext();
</script>
<template>
  <div class="table-wrap"><table><thead><tr><th>选择</th><th>订单号</th><th>创建时间</th><th>业务状态</th><th>文件数/容量</th><th>云端</th><th>热备</th><th>冷备/批次</th></tr></thead>
    <tbody><tr v-for="o in orders" :key="o.id" :class="{selected:selectedOrder?.id===o.id}" @click="selectOrder(o)">
      <td><input type="checkbox" :checked="selectedIds.includes(o.id)" @click.stop="toggleSelected(o)" /></td><td>{{o.orderCode || o.sourceOrderId}}</td><td>{{o.orderCreatedAt || '-'}}</td><td><status-badge :value="o.sourceStatus" /></td><td>{{o.assetCount ?? o.fileCount ?? '-'}} / {{o.totalBytes ?? o.fileBytes ?? '-'}}</td><td>{{o.cloudRollup || '-'}}</td><td>{{o.hotRollup || '-'}}</td><td>{{o.coldRollup || '-'}}<span v-if="o.coldBatchNo"> · {{o.coldBatchNo}}</span></td>
    </tr></tbody></table><empty-state v-if="!orders.length" message="暂无订单，请调整筛选条件" /></div>
</template>
