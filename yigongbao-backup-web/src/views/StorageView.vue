<script setup>
import { useBackupContext } from '../composables/useBackupContext.js';
const { storagePath, storage, probeStorage } = useBackupContext();
</script>
<template><div class="page-stack"><section class="page-heading"><div><div class="eyebrow">CAPACITY</div><h2>容量监控</h2><p>探测热备 NAS 或本地目录的总容量、已用空间与可用空间。</p></div></section><el-card shadow="never" class="page-card"><el-form inline label-position="top" @submit.prevent="probeStorage"><el-form-item label="受控存储目录"><el-input v-model.trim="storagePath" style="width:420px" placeholder="例如 D:/YiGongBao/backup/data" clearable /></el-form-item><el-form-item label="操作"><el-button type="primary" @click="probeStorage"><el-icon><Search /></el-icon>查询容量</el-button></el-form-item></el-form><el-divider /><el-empty v-if="!storage" description="输入目录后查询容量" /><pre v-else class="json-preview">{{ JSON.stringify(storage, null, 2) }}</pre></el-card></div></template>
