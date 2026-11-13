<script setup>
import { formatBytes, formatCount } from '../../overviewModel.js';

defineProps({ summary: Object });
defineEmits(['navigate']);

const statisticValueStyle = Object.freeze({ fontSize:'32px', fontWeight:700 });
</script>

<template>
  <el-row :gutter="16">
    <el-col :xs="24" :md="8">
      <el-card shadow="never">
        <template #header>
          <el-row justify="space-between" align="middle">
            <el-text tag="strong">订单统计</el-text>
            <el-button link type="primary" @click="$emit('navigate', { actionRoute: 'orders', actionQuery: { backupMode: 'REAL' } })">
              查看订单<el-icon><ArrowRight /></el-icon>
            </el-button>
          </el-row>
        </template>
        <el-statistic :value="summary?.orderStatistics?.total ?? 0" :formatter="() => formatCount(summary?.orderStatistics?.total)" :value-style="statisticValueStyle" />
        <el-divider />
        <el-descriptions :column="2">
          <el-descriptions-item label="热备">{{ formatCount(summary?.orderStatistics?.hot) }}</el-descriptions-item>
          <el-descriptions-item label="冷备">{{ formatCount(summary?.orderStatistics?.cold) }}</el-descriptions-item>
        </el-descriptions>
      </el-card>
    </el-col>

    <el-col :xs="24" :md="8">
      <el-card shadow="never">
        <template #header><el-text tag="strong">本地备份资产</el-text></template>
        <el-statistic :value="summary?.readableFiles?.logicalFileCount ?? 0" :formatter="() => formatCount(summary?.readableFiles?.logicalFileCount)" :value-style="statisticValueStyle" />
        <el-divider />
        <el-descriptions :column="4">
          <el-descriptions-item label="订单包">{{ formatCount(summary?.readableFiles?.orderPackageCount) }}</el-descriptions-item>
          <el-descriptions-item label="文件明细">{{ formatCount(summary?.readableFiles?.logicalFileCount) }}</el-descriptions-item>
          <el-descriptions-item label="容量">{{ formatBytes(summary?.readableFiles?.managedArtifactBytes) }}</el-descriptions-item>
          <el-descriptions-item label="元数据">{{ formatCount(summary?.readableFiles?.metadataRecordCount) }}</el-descriptions-item>
        </el-descriptions>
      </el-card>
    </el-col>

    <el-col :xs="24" :md="8">
      <el-card shadow="never">
        <template #header><el-text tag="strong">今日备份结果</el-text></template>
        <el-statistic :value="summary?.todayBackup?.total ?? 0" :formatter="() => formatCount(summary?.todayBackup?.total)" :value-style="statisticValueStyle" />
        <el-divider />
        <el-descriptions :column="3">
          <el-descriptions-item label="完整">{{ formatCount(summary?.todayBackup?.completeSucceeded) }}</el-descriptions-item>
          <el-descriptions-item label="降级">{{ formatCount(summary?.todayBackup?.incompleteSucceeded) }}</el-descriptions-item>
          <el-descriptions-item label="失败">{{ formatCount(summary?.todayBackup?.failed) }}</el-descriptions-item>
        </el-descriptions>
      </el-card>
    </el-col>
  </el-row>
</template>
