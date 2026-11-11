<script setup>
import { useBackupContext } from '../../composables/useBackupContext.js';
const { keyword, createdFrom, createdTo, status, businessType, needsPhysicalDelivery,
  cloudRollup, hotRollup, coldRollup, backupMode, storageAccessible, storageIssue,
  orderFilterOptions, loadOrders, resetOrderFilters } = useBackupContext();

function submit() {
  // 用户主动使用常规筛选时退出概览下钻语境，避免两组筛选条件互相遮蔽。
  backupMode.value = '';
  storageAccessible.value = '';
  storageIssue.value = '';
  loadOrders(1);
}
</script>
<template>
  <el-form class="order-filter" label-position="top" @submit.prevent="submit">
    <div class="order-filter-grid">
      <el-form-item class="keyword-filter" label="关键词"><el-input v-model.trim="keyword" placeholder="订单编号、虚拟单号、机构、医院、患者、业务员、医生或重建项目" clearable @keyup.enter="submit" /></el-form-item>
      <el-form-item label="订单创建时间"><el-date-picker v-model="createdFrom" type="date" value-format="YYYY-MM-DD" placeholder="开始日期" clearable /></el-form-item>
      <el-form-item label="至"><el-date-picker v-model="createdTo" type="date" value-format="YYYY-MM-DD" placeholder="结束日期" clearable /></el-form-item>
      <el-form-item label="订单状态"><el-select v-model="status" placeholder="全部状态" clearable filterable><el-option v-for="item in orderFilterOptions.orderStatuses" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item>
      <el-form-item label="业务类型"><el-select v-model="businessType" placeholder="全部业务类型" clearable><el-option v-for="item in orderFilterOptions.businessTypes" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item>
      <el-form-item class="physical-filter" label="实体交付"><el-select v-model="needsPhysicalDelivery" placeholder="全部" clearable><el-option v-for="item in orderFilterOptions.physicalDeliveries" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item>
      <el-form-item class="storage-filter" label="云端 / 热备 / 冷备"><div class="filter-triple"><el-select v-model="cloudRollup" placeholder="云端" clearable><el-option v-for="item in orderFilterOptions.storageStates" :key="item.value" :label="item.label" :value="item.value" /></el-select><el-select v-model="hotRollup" placeholder="热备" clearable><el-option v-for="item in orderFilterOptions.localReplicaStates" :key="item.value" :label="item.label" :value="item.value" /></el-select><el-select v-model="coldRollup" placeholder="冷备" clearable><el-option v-for="item in orderFilterOptions.localReplicaStates" :key="item.value" :label="item.label" :value="item.value" /></el-select></div></el-form-item>
      <div class="filter-actions"><el-button type="primary" native-type="submit">查询</el-button><el-button @click="resetOrderFilters">重置</el-button></div>
    </div>
  </el-form>
</template>
