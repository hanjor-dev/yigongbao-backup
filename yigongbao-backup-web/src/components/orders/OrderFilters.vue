<script setup>
import { useBackupContext } from '../../composables/useBackupContext.js';
const { orderCode, createdFrom, createdTo, snapshotStatus, sourceStatus, orgName, hospitalName,
  patientName, doctorName, businessType, phase, status, cloudRollup, hotRollup, coldRollup } = useBackupContext();
</script>
<template>
  <el-form class="order-filter" inline label-position="top">
    <el-form-item label="订单号"><el-input v-model.trim="orderCode" placeholder="输入订单号" clearable /></el-form-item>
    <el-form-item label="创建时间"><el-date-picker v-model="createdFrom" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" placeholder="开始时间" clearable /></el-form-item>
    <el-form-item label="至"><el-date-picker v-model="createdTo" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" placeholder="结束时间" clearable /></el-form-item>
    <el-form-item label="快照状态"><el-input v-model.trim="snapshotStatus" placeholder="如 AVAILABLE" clearable /></el-form-item>
    <el-form-item label="业务状态"><el-input v-model.trim="sourceStatus" placeholder="输入状态" clearable /></el-form-item>
    <el-form-item label="机构"><el-input v-model.trim="orgName" placeholder="机构名称" clearable /></el-form-item>
    <el-form-item label="医院"><el-input v-model.trim="hospitalName" placeholder="医院名称" clearable /></el-form-item>
    <el-form-item label="患者"><el-input v-model.trim="patientName" placeholder="患者姓名" clearable /></el-form-item>
    <el-form-item label="医生"><el-input v-model.trim="doctorName" placeholder="医生姓名" clearable /></el-form-item>
    <el-form-item label="业务类型"><el-input v-model.trim="businessType" placeholder="如 11.1" clearable /></el-form-item>
    <el-form-item label="阶段"><el-input v-model.trim="phase" placeholder="阶段编码" clearable /></el-form-item>
    <el-form-item label="状态"><el-input v-model.trim="status" placeholder="状态编码" clearable /></el-form-item>
    <el-form-item label="云端 / 热备 / 冷备"><div class="filter-triple"><el-select v-model="cloudRollup" placeholder="云端" clearable><el-option label="存在" value="PRESENT" /><el-option label="缺失" value="ABSENT" /></el-select><el-select v-model="hotRollup" placeholder="热备" clearable><el-option label="存在" value="PRESENT" /><el-option label="缺失" value="ABSENT" /></el-select><el-select v-model="coldRollup" placeholder="冷备" clearable><el-option label="存在" value="PRESENT" /><el-option label="缺失" value="ABSENT" /></el-select></div></el-form-item>
  </el-form>
</template>
