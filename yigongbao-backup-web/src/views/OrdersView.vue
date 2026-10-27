<script setup>
import { useBackupContext } from '../composables/useBackupContext.js';
import OrderFilters from '../components/orders/OrderFilters.vue';
import OrderTable from '../components/orders/OrderTable.vue';
import OrderDetail from '../components/orders/OrderDetail.vue';
const { isAdmin, selectedIds, createdFrom, createdTo, loadOrders, restoreBatch, orderPage, orderPages, orderTotal, orderSize, changeOrderPage, changeOrderSize } = useBackupContext();
</script>
<template>
  <section class="card page-card"><div class="toolbar"><div><div class="eyebrow">ORDER ASSETS</div><h2>订单文件</h2></div><div class="toolbar-actions"><button @click="loadOrders(1)">查询</button><button v-if="isAdmin" class="primary" @click="restoreBatch" :disabled="!selectedIds.length && !createdFrom && !createdTo">批量/时间范围恢复</button></div></div>
    <OrderFilters /><OrderTable />
    <div class="pager" v-if="orderTotal || orderPages"><button @click="changeOrderPage(orderPage - 1)" :disabled="orderPage <= 1">上一页</button><span>第 {{orderPage}} / {{orderPages || 1}} 页，共 {{orderTotal}} 条</span><select v-model.number="orderSize" @change="changeOrderSize"><option :value="20">20 条/页</option><option :value="50">50 条/页</option><option :value="100">100 条/页</option></select><button @click="changeOrderPage(orderPage + 1)" :disabled="!orderPages || orderPage >= orderPages">下一页</button></div>
    <OrderDetail />
  </section>
</template>
