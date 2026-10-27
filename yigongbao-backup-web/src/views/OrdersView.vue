<script setup>
import { useBackupContext } from '../composables/useBackupContext.js';
import OrderFilters from '../components/orders/OrderFilters.vue';
import OrderTable from '../components/orders/OrderTable.vue';
import OrderDetail from '../components/orders/OrderDetail.vue';
const { isAdmin, selectedIds, createdFrom, createdTo, loadOrders, restoreBatch, orderPage, orderPages, orderTotal, orderSize, changeOrderPage, changeOrderSize } = useBackupContext();
</script>
<template>
  <div class="page-stack"><section class="page-heading"><div><div class="eyebrow">ORDER ASSETS</div><h2>订单文件</h2><p>查看订单快照与三地存储状态，选择订单后可下载、补传或发起线上恢复。</p></div><div class="toolbar-actions"><el-tag v-if="selectedIds.length" type="primary" effect="light">已选择 {{ selectedIds.length }} 单</el-tag><el-button @click="loadOrders(1)"><el-icon><Refresh /></el-icon>刷新列表</el-button><el-button v-if="isAdmin" type="primary" :disabled="!selectedIds.length && !createdFrom && !createdTo" @click="restoreBatch"><el-icon><Upload /></el-icon>批量恢复</el-button></div></section><el-card class="filter-card" shadow="never"><OrderFilters /></el-card><el-card class="table-card" shadow="never"><OrderTable /><div class="table-footer" v-if="orderTotal || orderPages"><span>共 {{ orderTotal }} 条订单</span><el-pagination v-model:current-page="orderPage" v-model:page-size="orderSize" :page-sizes="[20, 50, 100]" :total="orderTotal" layout="sizes, prev, pager, next" @current-change="changeOrderPage" @size-change="changeOrderSize" /></div></el-card><OrderDetail /></div>
</template>
