<script setup>
import { computed } from 'vue';
import { useBackupContext } from '../../composables/useBackupContext.js';

const { selectedJob, jobOrders, jobFiles, jobLoading, jobProgress, cancelJob, retryJob } = useBackupContext();
const terminalStates = ['SUCCEEDED', 'FAILED', 'CANCELLED', 'CANCELED'];
const jobState = (job) => job?.state || job?.status || '';
const progress = computed(() => selectedJob.value ? jobProgress(selectedJob.value) : 0);
</script>

<template>
  <el-card v-if="selectedJob" class="job-detail detail" shadow="never">
    <template #header>
      <div class="detail-heading">
        <div>
          <span class="eyebrow">TASK DETAIL</span>
          <h3>任务 {{ selectedJob.id }}</h3>
        </div>
        <div class="detail-actions">
          <el-tag :type="jobState(selectedJob) === 'SUCCEEDED' ? 'success' : jobState(selectedJob) === 'FAILED' ? 'danger' : 'info'" effect="light">
            {{ jobState(selectedJob) || 'UNKNOWN' }}
          </el-tag>
          <el-button v-if="!terminalStates.includes(jobState(selectedJob))" type="danger" plain @click="cancelJob()">取消任务</el-button>
          <el-button v-if="terminalStates.includes(jobState(selectedJob)) && jobState(selectedJob) !== 'SUCCEEDED'" type="primary" plain @click="retryJob()">重试任务</el-button>
        </div>
      </div>
    </template>

    <div class="job-progress-panel">
      <div class="progress-meta"><span>整体进度</span><strong>{{ progress }}%</strong><span v-if="jobLoading" class="muted">正在刷新…</span></div>
      <el-progress :percentage="progress" :status="jobState(selectedJob) === 'FAILED' ? 'exception' : jobState(selectedJob) === 'SUCCEEDED' ? 'success' : undefined" :stroke-width="10" />
    </div>
    <el-alert v-if="selectedJob.failureCode" type="error" :closable="false" show-icon class="mb-16">
      失败原因：{{ selectedJob.failureCode }} {{ selectedJob.failureEvidence || '' }}
    </el-alert>

    <h4>订单明细</h4>
    <el-table :data="jobOrders" stripe border class="mb-24" empty-text="暂无订单明细">
      <el-table-column label="订单" min-width="180"><template #default="{ row }">{{ row.sourceOrderId || row.snapshotId }}</template></el-table-column>
      <el-table-column label="状态" width="140"><template #default="{ row }"><status-badge :value="row.status" /></template></el-table-column>
      <el-table-column label="失败原因" min-width="260"><template #default="{ row }"><span class="error-text">{{ row.failureCode || '' }} {{ row.failureMessage || '' }}</span></template></el-table-column>
    </el-table>

    <h4>文件明细</h4>
    <el-table :data="jobFiles" stripe border empty-text="暂无文件明细">
      <el-table-column prop="sourceOrderId" label="订单" min-width="160" />
      <el-table-column prop="assetIdentity" label="资产" min-width="220" />
      <el-table-column prop="step" label="步骤" width="140" />
      <el-table-column label="状态" width="130"><template #default="{ row }"><status-badge :value="row.status" /></template></el-table-column>
      <el-table-column label="进度" width="180"><template #default="{ row }">{{ row.bytesDone || 0 }} / {{ row.bytesTotal || 0 }}</template></el-table-column>
      <el-table-column label="失败原因" min-width="260"><template #default="{ row }"><span class="error-text">{{ row.failureCode || '' }} {{ row.failureMessage || '' }}</span></template></el-table-column>
    </el-table>
  </el-card>
</template>
