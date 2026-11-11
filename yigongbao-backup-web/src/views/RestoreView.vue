<script setup>
import { onMounted } from 'vue';
import RestoreTaskTable from '../components/restores/RestoreTaskTable.vue';
import RestoreTaskDetail from '../components/restores/RestoreTaskDetail.vue';
import { useRestoreTasks } from '../composables/useRestoreTasks.js';
// 在 setup 顶层解构 ref，Vue 模板会自动解包；不能以 restore.tasks 的嵌套方式传入 el-table。
const {
  tasks, current, size, total, selected, orders, orderCurrent, orderSize, orderTotal, orderFiles, orderFileLoading, loading, message,
  logDialogVisible, logTitle, logRecords, logCurrent, logSize, logTotal, logLoading,
  selectedProgress, refresh, selectTask, cancel, retry, changePage, changeSize, changeOrderPage, changeOrderSize,
  expandOrder, changeOrderFilePage, changeOrderFileSize, openTaskLogs, openOrderLogs, changeLogPage, changeLogSize
} = useRestoreTasks();
onMounted(async () => {
  await refresh();
  const focused = sessionStorage.getItem('backup-restore-task-focus');
  if (focused) {
    sessionStorage.removeItem('backup-restore-task-focus');
    await selectTask({ id: focused });
  }
});
</script>

<template>
  <div class="page-stack"><section class="page-heading"><div><h2>线上恢复</h2><p>跟踪热备订单包上传、线上校验与文件链接回写结果。恢复入口位于“订单文件”页。</p></div><el-button type="primary" plain @click="refresh">刷新任务</el-button></section>
    <el-alert title="每个订单的 ZIP 包会独立准备、上传并提交。任一关联文件失败则该订单恢复失败；线上已删除订单会自动跳过。" type="info" show-icon :closable="false" />
    <el-alert v-if="message" class="mb-16" type="warning" show-icon :closable="false" :title="message" />
    <el-card shadow="never" class="table-card mb-16"><template #header>线上恢复任务列表</template><RestoreTaskTable :tasks="tasks" :current="current" :size="size" :total="total" @select="selectTask" @page-change="changePage" @size-change="changeSize" /></el-card>
    <RestoreTaskDetail :task="selected" :orders="orders" :order-files="orderFiles" :order-file-loading="orderFileLoading" :order-current="orderCurrent" :order-size="orderSize" :order-total="orderTotal" :progress="selectedProgress" :loading="loading" :log-visible="logDialogVisible" :log-title="logTitle" :logs="logRecords" :log-current="logCurrent" :log-size="logSize" :log-total="logTotal" :log-loading="logLoading" @cancel="cancel" @retry="retry" @page-change="changeOrderPage" @size-change="changeOrderSize" @expand-order="expandOrder" @file-page-change="changeOrderFilePage" @file-size-change="changeOrderFileSize" @open-task-logs="openTaskLogs" @open-order-logs="openOrderLogs" @log-page-change="changeLogPage" @log-size-change="changeLogSize" @update:log-visible="logDialogVisible = $event" />
  </div>
</template>
