<script setup>
import { computed } from 'vue';
import { useBackupContext } from '../../composables/useBackupContext.js';
import { orderBusinessFields } from '../../taskCenter.js';
import OrderBusinessFields from '../orders/OrderBusinessFields.vue';

const { selectedJob, jobOrders, jobFiles, jobLoading, jobProgress, cancelJob, retryJob } = useBackupContext();
const terminalStates = ['SUCCEEDED', 'FAILED', 'PARTIAL_FAILED', 'CANCELLED', 'CANCELED'];
const jobState = (job) => job?.state || job?.status || '';
const progress = computed(() => selectedJob.value ? jobProgress(selectedJob.value) : 0);
const business = (row) => orderBusinessFields(row.orderBusinessJson);
const simulation = computed(() => selectedJob.value?.executionMode === 'METADATA_SIMULATION');
const stateLabel = computed(() => jobState(selectedJob.value) === 'RETRYING_FAILED_ORDERS' ? '失败订单重试中'
  : simulation.value && jobState(selectedJob.value) === 'SUCCEEDED' ? '模拟完成' : jobState(selectedJob.value));
const bytes = (value) => { const n = Number(value || 0); if (n < 1024) return `${n} B`; if (n < 1048576) return `${(n / 1024).toFixed(2)} KB`; if (n < 1073741824) return `${(n / 1048576).toFixed(2)} MB`; return `${(n / 1073741824).toFixed(2)} GB`; };
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
          <el-tag v-if="simulation" type="warning">元数据模拟</el-tag>
          <el-tag :type="jobState(selectedJob) === 'SUCCEEDED' ? 'success' : jobState(selectedJob) === 'FAILED' ? 'danger' : 'info'" effect="light">
            {{ stateLabel || 'UNKNOWN' }}
          </el-tag>
          <el-tag v-if="selectedJob.cancelRequested" type="warning">取消中</el-tag>
          <el-button v-if="!terminalStates.includes(jobState(selectedJob)) && !selectedJob.cancelRequested" type="danger" plain @click="cancelJob()">取消任务</el-button>
          <el-button v-if="['FAILED', 'PARTIAL_FAILED'].includes(jobState(selectedJob)) && (selectedJob.orderFailed || 0) > 0" type="primary" plain @click="retryJob()">重试失败订单</el-button>
        </div>
      </div>
    </template>

    <div class="job-progress-panel">
      <div class="progress-meta"><span>整体进度</span><strong>{{ progress }}%</strong><span v-if="jobLoading" class="muted">正在刷新…</span></div>
      <el-progress :percentage="progress" :status="jobState(selectedJob) === 'FAILED' ? 'exception' : jobState(selectedJob) === 'SUCCEEDED' ? 'success' : undefined" :stroke-width="10" />
    </div>
    <el-descriptions v-if="simulation" :column="4" border class="mb-16">
      <el-descriptions-item label="执行结果">{{ jobState(selectedJob) === 'SUCCEEDED' ? '模拟完成' : jobState(selectedJob) }}</el-descriptions-item>
      <el-descriptions-item label="Manifest">成功 {{ selectedJob.manifestSucceeded || 0 }} / 失败 {{ selectedJob.manifestFailed || 0 }}</el-descriptions-item>
      <el-descriptions-item label="预计文件">{{ selectedJob.expectedFileTotal || 0 }}</el-descriptions-item>
      <el-descriptions-item label="预计容量">{{ bytes(selectedJob.expectedBytes) }}</el-descriptions-item>
    </el-descriptions>
    <el-alert v-if="selectedJob.failureCode" type="error" :closable="false" show-icon class="mb-16">
      失败原因：{{ selectedJob.failureCode }} {{ selectedJob.failureEvidence || '' }}
    </el-alert>

    <h4>订单明细</h4>
    <el-table :data="jobOrders" stripe border class="mb-24" empty-text="暂无订单明细">
      <el-table-column type="expand"><template #default="{ row }"><OrderBusinessFields :value="row.orderBusinessJson" /></template></el-table-column>
      <el-table-column label="订单号" min-width="180"><template #default="{ row }">{{ row.orderCode || row.sourceOrderId }}</template></el-table-column>
      <el-table-column prop="orderCreatedAt" label="创建时间" min-width="170" />
      <el-table-column label="所属机构" min-width="180" show-overflow-tooltip><template #default="{ row }">{{ business(row).orgName || '-' }}</template></el-table-column>
      <el-table-column label="地区" min-width="130"><template #default="{ row }">{{ business(row).areaName || '-' }}</template></el-table-column>
      <el-table-column label="业务状态" width="120"><template #default="{ row }"><status-badge :value="row.sourceStatus" /></template></el-table-column>
      <el-table-column label="状态" width="140"><template #default="{ row }"><status-badge :value="row.status" /></template></el-table-column>
      <el-table-column v-if="simulation" prop="expectedFileCount" label="预计文件" width="100" />
      <el-table-column v-if="simulation" label="预计容量" width="130"><template #default="{ row }">{{ bytes(row.expectedBytes) }}</template></el-table-column>
      <el-table-column v-if="simulation" prop="simulationPath" label="模拟目录" min-width="260" show-overflow-tooltip />
      <el-table-column label="失败原因" min-width="260"><template #default="{ row }"><span class="error-text">{{ row.failureCode || '' }} {{ row.failureMessage || '' }}</span></template></el-table-column>
    </el-table>

    <h4 v-if="!simulation">文件明细</h4>
    <el-table v-if="!simulation" :data="jobFiles" stripe border empty-text="暂无文件明细">
      <el-table-column prop="sourceOrderId" label="订单" min-width="160" />
      <el-table-column prop="assetIdentity" label="资产" min-width="220" />
      <el-table-column prop="step" label="步骤" width="140" />
      <el-table-column label="状态" width="130"><template #default="{ row }"><status-badge :value="row.status" /></template></el-table-column>
      <el-table-column label="进度" width="180"><template #default="{ row }">{{ row.bytesDone || 0 }} / {{ row.bytesTotal || 0 }}</template></el-table-column>
      <el-table-column label="失败原因" min-width="260"><template #default="{ row }"><span class="error-text">{{ row.failureCode || '' }} {{ row.failureMessage || '' }}</span></template></el-table-column>
    </el-table>
  </el-card>
</template>
