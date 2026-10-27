<script setup>
import { useBackupContext } from '../../composables/useBackupContext.js';
const { orderCode, createdFrom, createdTo, snapshotStatus, sourceStatus, cloudRollup, hotRollup, coldRollup } = useBackupContext();
</script>
<template>
  <div class="filters">
    <input v-model.trim="orderCode" placeholder="订单号" /><input v-model="createdFrom" type="datetime-local" /><input v-model="createdTo" type="datetime-local" />
    <input v-model.trim="snapshotStatus" placeholder="快照状态" /><input v-model.trim="sourceStatus" placeholder="业务状态" />
    <select v-model="cloudRollup"><option value="">云端全部</option><option value="PRESENT">云端存在</option><option value="ABSENT">云端缺失</option></select>
    <select v-model="hotRollup"><option value="">热备全部</option><option value="PRESENT">热备存在</option><option value="ABSENT">热备缺失</option></select>
    <select v-model="coldRollup"><option value="">冷备全部</option><option value="PRESENT">冷备存在</option><option value="ABSENT">冷备缺失</option></select>
  </div>
</template>
