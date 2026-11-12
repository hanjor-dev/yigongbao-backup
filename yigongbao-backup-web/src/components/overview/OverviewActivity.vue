<script setup>
import { statusLabel } from '../StatusBadge.js';
import { activityPresentation, elementTagType, formatTime } from '../../overviewModel.js';

defineProps({ activities: Array });
defineEmits(['navigate']);
</script>

<template>
  <el-card shadow="never">
    <template #header><el-text tag="strong">近期活动</el-text></template>
    <el-empty v-if="!activities?.length" description="暂无近期活动" :image-size="72" />
    <el-scrollbar v-else height="452px">
      <el-timeline>
        <el-timeline-item
          v-for="item in activities"
          :key="item.activityId"
          :timestamp="formatTime(item.finishedAt || item.startedAt || item.requestedAt)"
          :type="elementTagType(item.status)"
          placement="top"
        >
          <el-container direction="vertical">
            <el-space direction="vertical" fill :size="8">
              <el-row align="middle">
                <el-col :span="18">
                  <el-space wrap :size="6">
                    <el-text tag="strong" line-clamp="1">{{ item.name || activityPresentation(item).typeLabel }}</el-text>
                    <el-tag size="small" :type="elementTagType(item.status)">{{ statusLabel(item.status) }}</el-tag>
                  </el-space>
                </el-col>
                <el-col :span="6">
                  <el-row justify="end" align="middle">
                    <el-button v-if="activityPresentation(item).interactive" link type="primary" size="small" @click="$emit('navigate', item)">详情</el-button>
                  </el-row>
                </el-col>
              </el-row>
              <el-row>
                <el-col :span="8"><el-text size="small">完整 {{ item.completeSucceeded ?? 0 }}</el-text></el-col>
                <el-col :span="8"><el-text size="small">降级 {{ item.incompleteSucceeded ?? 0 }}</el-text></el-col>
                <el-col :span="8"><el-text size="small">失败 {{ item.failed ?? 0 }}</el-text></el-col>
              </el-row>
              <el-text v-if="item.failureSummary" type="danger" size="small" :line-clamp="2">{{ item.failureSummary }}</el-text>
              <el-text v-else-if="item.storageSummary" type="info" size="small" :line-clamp="2">{{ item.storageSummary }}</el-text>
            </el-space>
          </el-container>
        </el-timeline-item>
      </el-timeline>
    </el-scrollbar>
  </el-card>
</template>
