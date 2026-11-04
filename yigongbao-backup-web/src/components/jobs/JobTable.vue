<script setup>
import { useBackupContext } from '../../composables/useBackupContext.js';
const { jobs, jobPage, jobSize, jobTotal, jobProgress, selectJob, cancelJob, retryJob, changeJobPage, changeJobSize } = useBackupContext();
const terminalStates = ['SUCCEEDED','FAILED','PARTIAL_FAILED','CANCELLED','CANCELED'];
const jobState = (job) => job.state || job.status || '';
const displayTime = (value) => value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '-';
const fileSummary = (job) => Number(job.fileTotal || 0) > 0
  ? `成功 ${job.fileCompleted || 0} / 失败 ${job.fileFailed || 0} / 共 ${job.fileTotal}` : '未获取文件清单';
const orderPending = (job) => Math.max(0, Number(job.orderTotal || 0) - Number(job.orderCompleted || 0) - Number(job.orderFailed || 0));
const simulation = (job) => job.executionMode === 'METADATA_SIMULATION';
const statusText = (job) => jobState(job) === 'RETRYING_FAILED_ORDERS' ? '失败订单重试中'
  : simulation(job) && jobState(job) === 'SUCCEEDED' ? '模拟完成' : jobState(job);
const expectedSummary = (job) => `Manifest 成功 ${job.manifestSucceeded || 0} / 失败 ${job.manifestFailed || 0}；预计文件 ${job.expectedFileTotal || 0}`;
const bytes = (value) => { const n = Number(value || 0); if (n < 1024) return `${n} B`; if (n < 1048576) return `${(n / 1024).toFixed(2)} KB`; if (n < 1073741824) return `${(n / 1048576).toFixed(2)} MB`; return `${(n / 1073741824).toFixed(2)} GB`; };
const capacitySummary = (job) => `源文件 ${bytes(job.expectedBytes)} / ZIP ${bytes(job.packageBytes)}`;
</script>

<template>
  <el-table :data="jobs" class="data-table" highlight-current-row row-key="id">
    <el-table-column label="任务编号" min-width="210"><template #default="{ row }"><div class="mono">{{ row.jobNo || ('#' + row.id) }}</div><small class="muted">ID: {{ row.id }}</small></template></el-table-column>
    <el-table-column label="创建时间" min-width="175"><template #default="{ row }">{{ displayTime(row.requestedAt) }}</template></el-table-column>
    <el-table-column label="模式/状态" width="170"><template #default="{ row }"><el-tag v-if="simulation(row)" type="warning" size="small">元数据模拟</el-tag><status-badge :value="row.cancelRequested ? 'CANCELLED' : jobState(row)" :label="row.cancelRequested ? '取消中' : statusText(row)" /></template></el-table-column>
    <el-table-column label="进度" width="170"><template #default="{ row }"><el-progress :percentage="jobProgress(row)" :stroke-width="7" /></template></el-table-column>
    <el-table-column label="订单" min-width="230"><template #default="{ row }"><span class="success-text">成功 {{ row.orderCompleted || 0 }}</span> / <span class="error-text">失败 {{ row.orderFailed || 0 }}</span> / 待处理 {{ orderPending(row) }} / 共 {{ row.orderTotal || 0 }}</template></el-table-column>
    <el-table-column label="文件" min-width="250"><template #default="{ row }">{{ simulation(row) ? expectedSummary(row) : fileSummary(row) }}</template></el-table-column>
    <el-table-column label="容量（压缩前 / ZIP）" min-width="210"><template #default="{ row }">{{ capacitySummary(row) }}</template></el-table-column>
    <el-table-column label="失败原因" min-width="220" show-overflow-tooltip><template #default="{ row }"><span class="error-text">{{ row.failureCode || '-' }} {{ row.failureEvidence || '' }}</span></template></el-table-column>
    <el-table-column label="操作" width="205" fixed="right"><template #default="{ row }"><el-button size="small" @click="selectJob(row)">详情</el-button><el-button v-if="!terminalStates.includes(jobState(row)) && !row.cancelRequested" size="small" type="warning" plain @click="cancelJob(row)">取消</el-button><el-button v-if="['FAILED','PARTIAL_FAILED'].includes(jobState(row)) && (row.orderFailed || 0) > 0" size="small" type="primary" plain @click="retryJob(row)">重试失败订单</el-button></template></el-table-column>
    <template #empty><el-empty description="暂无备份任务" /></template>
  </el-table>
  <el-pagination v-if="jobTotal > 0" class="table-pagination" background layout="total, sizes, prev, pager, next"
    :current-page="jobPage" :page-size="jobSize" :total="jobTotal" :page-sizes="[10, 20, 50, 100]"
    @current-change="changeJobPage" @size-change="changeJobSize" />
</template>
