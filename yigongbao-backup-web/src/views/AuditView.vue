<script setup>
import { useBackupContext } from '../composables/useBackupContext.js';
const { auditRows, loadAudit } = useBackupContext();
</script>

<template>
  <section class="card page-card">
    <div class="toolbar"><div><div class="eyebrow">TRACEABILITY</div><h2>审计日志</h2></div><button @click="loadAudit">刷新</button></div>
    <div class="table-wrap">
      <table><thead><tr><th>时间</th><th>操作人</th><th>操作</th><th>资源</th><th>结果</th><th>请求号</th></tr></thead>
      <tbody>
        <tr v-for="row in auditRows" :key="row.id || row.requestId"><td>{{row.createdAt || row.time || '-'}}</td><td>{{row.operatorName || row.operator || row.username || '-'}}</td><td>{{row.action || row.operation || '-'}}</td><td>{{row.resource || row.target || '-'}}</td><td><status-badge :value="row.result || row.status" /></td><td>{{row.requestId || '-'}}</td></tr>
      </tbody></table>
      <empty-state v-if="!auditRows.length" message="暂无审计日志" />
    </div>
  </section>
</template>
