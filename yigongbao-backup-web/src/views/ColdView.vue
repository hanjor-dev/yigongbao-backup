<script setup>
import { useBackupContext } from '../composables/useBackupContext.js';
import ColdBatchTable from '../components/cold/ColdBatchTable.vue';
import ColdBatchDetail from '../components/cold/ColdBatchDetail.vue';
const c = useBackupContext();
const { coldBatches, coldDisks, coldDiskId, coldFrom, coldTo, diskCode, diskLabel, coldArchives, coldArchive, coldOrderIds, coldRoot, writePackages, selectedBatchId, coldBusy, coldDiagnostics, registerColdDisk, createColdBatch, scanColdImports, restoreColdImport, writeCold } = c;
</script>
<template>
  <section class="card page-card"><div class="toolbar"><div><div class="eyebrow">COLD ARCHIVE</div><h2>冷备导入与批次</h2></div><button @click="scanColdImports" :disabled="coldBusy">扫描导入区</button></div><div v-if="coldDiagnostics.length" class="diagnostics"><strong>操作提示</strong><ul><li v-for="item in coldDiagnostics" :key="item">{{item}}</li></ul></div>
    <div class="form-grid"><div class="detail"><h3>登记冷备盘</h3><div class="inline-form"><input v-model.trim="diskCode" placeholder="盘标识/序列号" /><input v-model.trim="diskLabel" placeholder="人工标签" /><button @click="registerColdDisk">登记</button></div></div><div class="detail"><h3>创建冷备批次</h3><div class="inline-form"><select v-model="coldDiskId"><option value="">选择管理员指定冷备盘</option><option v-for="d in coldDisks" :key="d.id" :value="d.id">{{d.label || d.diskCode || d.id}} · {{d.status}}</option></select><input v-model="coldFrom" type="datetime-local" /><input v-model="coldTo" type="datetime-local" /><button class="primary" @click="createColdBatch">创建批次</button></div></div></div>
    <div class="detail"><h3>冷备盘重插/导入</h3><p class="muted">系统不会自动操作冷备硬盘。请运维人员将目标批次压缩包手动复制到导入区后扫描。</p><div class="inline-form"><select v-model="coldArchive"><option value="">请选择扫描到的批次压缩包</option><option v-for="archive in coldArchives" :key="archive" :value="archive">{{archive}}</option></select><input v-model.trim="coldOrderIds" placeholder="订单号，多个用逗号分隔；留空恢复全部" /><button class="primary" @click="restoreColdImport" :disabled="!coldArchive || coldBusy">恢复到热备区</button></div></div>
    <ColdBatchTable /><ColdBatchDetail />
    <div class="detail"><h3>管理员手动写出冷备</h3><div class="inline-form"><select v-model="selectedBatchId"><option value="">选择待写出批次</option><option v-for="b in coldBatches" :key="b.id" :value="b.id">{{b.batchNo}} · {{b.status}}</option></select><input v-model.trim="coldRoot" placeholder="管理员明确选择的冷备盘目录" /><input v-model.trim="writePackages" placeholder="热备订单包相对路径，逗号分隔" /><button @click="writeCold" :disabled="coldBusy">写入冷备盘</button></div></div>
  </section>
</template>
