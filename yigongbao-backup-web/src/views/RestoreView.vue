<script setup>
import { onMounted } from 'vue';
import RestoreTaskTable from '../components/restores/RestoreTaskTable.vue';
import RestoreTaskDetail from '../components/restores/RestoreTaskDetail.vue';
import { useRestoreTasks } from '../composables/useRestoreTasks.js';
import { useBackupContext } from '../composables/useBackupContext.js';

const { consumeRouteQuery } = useBackupContext();

const {
  tasks, current, size, total, selected, detailVisible, detailTab, orders, orderCurrent, orderSize, orderTotal,
  selectedOrder, orderDetailVisible, orderDetailTab, orderFiles, orderFileCurrent, orderFileSize, orderFileTotal, orderFileLoading,
  taskLogRecords, taskLogCurrent, taskLogSize, taskLogTotal, taskLogLoading,
  orderLogRecords, orderLogCurrent, orderLogSize, orderLogTotal, orderLogLoading,
  loading, message, selectedProgress,
  refresh, selectTask, closeTaskDetail, cancel, retry, changePage, changeSize, changeOrderPage, changeOrderSize,
  changeDetailTab, openOrderDetail, closeOrderDetail, changeOrderDetailTab,
  changeOrderFilePage, changeOrderFileSize, changeTaskLogPage, changeTaskLogSize, changeOrderLogPage, changeOrderLogSize
} = useRestoreTasks();

onMounted(async () => {
  await refresh();
  const { jobId } = consumeRouteQuery(['jobId']);
  if (jobId) await selectTask({ id: jobId });
});
</script>

<template>
  <div class="page-stack">
    <section class="page-heading">
      <div><h2>线上恢复</h2><p>跟踪热备订单包上传、线上校验与文件链接回写结果。恢复入口位于“订单文件”页。</p></div>
      <el-button type="primary" plain @click="refresh">刷新任务</el-button>
    </section>
    <el-alert title="每个订单的 ZIP 包会独立准备、上传并提交。任一关联文件失败则该订单恢复失败；线上已删除订单会自动跳过。" type="info" show-icon :closable="false" />
    <el-alert v-if="message" class="mb-16" type="warning" show-icon :closable="false" :title="message" />
    <el-card shadow="never" class="table-card mb-16">
      <template #header>线上恢复任务列表</template>
      <RestoreTaskTable :tasks="tasks" :current="current" :size="size" :total="total" @select="selectTask" @page-change="changePage" @size-change="changeSize" />
    </el-card>

    <RestoreTaskDetail
      v-model="detailVisible"
      :task="selected"
      :orders="orders"
      :order-current="orderCurrent"
      :order-size="orderSize"
      :order-total="orderTotal"
      :progress="selectedProgress"
      :loading="loading"
      :detail-tab="detailTab"
      :task-logs="taskLogRecords"
      :task-log-current="taskLogCurrent"
      :task-log-size="taskLogSize"
      :task-log-total="taskLogTotal"
      :task-log-loading="taskLogLoading"
      :selected-order="selectedOrder"
      :order-detail-visible="orderDetailVisible"
      :order-detail-tab="orderDetailTab"
      :order-files="orderFiles"
      :order-file-current="orderFileCurrent"
      :order-file-size="orderFileSize"
      :order-file-total="orderFileTotal"
      :order-file-loading="orderFileLoading"
      :order-logs="orderLogRecords"
      :order-log-current="orderLogCurrent"
      :order-log-size="orderLogSize"
      :order-log-total="orderLogTotal"
      :order-log-loading="orderLogLoading"
      @close="closeTaskDetail"
      @cancel="cancel"
      @retry="retry"
      @tab-change="changeDetailTab"
      @page-change="changeOrderPage"
      @size-change="changeOrderSize"
      @open-order-detail="openOrderDetail"
      @update:order-detail-visible="orderDetailVisible = $event"
      @close-order-detail="closeOrderDetail"
      @order-tab-change="changeOrderDetailTab"
      @file-page-change="changeOrderFilePage"
      @file-size-change="changeOrderFileSize"
      @task-log-page-change="changeTaskLogPage"
      @task-log-size-change="changeTaskLogSize"
      @order-log-page-change="changeOrderLogPage"
      @order-log-size-change="changeOrderLogSize"
    />
  </div>
</template>
