<script setup>
import { useBackupContext } from '../composables/useBackupContext.js';
const { storagePath, storage, probeStorage } = useBackupContext();
</script>

<template>
  <section class="card page-card"><div class="toolbar"><div><div class="eyebrow">CAPACITY</div><h2>容量监控</h2></div><button class="primary" @click="probeStorage">查询容量</button></div><div class="inline-form"><label>热备目录<input v-model.trim="storagePath" placeholder="例如 D:/backup-data" /></label></div><pre v-if="storage" class="json-preview">{{JSON.stringify(storage, null, 2)}}</pre><empty-state v-else message="输入热备目录后查询容量" /></section>
</template>
