<script setup>
import { computed } from 'vue';
import { use } from 'echarts/core';
import { BarChart, LineChart } from 'echarts/charts';
import {
  AriaComponent,
  DataZoomComponent,
  GridComponent,
  LegendComponent,
  TitleComponent,
  TooltipComponent
} from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';
import VChart from 'vue-echarts';
import { overviewTrendChartOption, trendPeriodPresentation, trendPointEnd } from '../../overviewModel.js';

use([BarChart, LineChart, AriaComponent, DataZoomComponent, GridComponent, LegendComponent, TitleComponent, TooltipComponent, CanvasRenderer]);

const props = defineProps({
  points: Array,
  observedAt: String,
  period: { type: String, default: 'WEEK' },
  loading: Boolean,
  error: Object
});
const emit = defineEmits(['navigate', 'period-change']);

const periodOptions = Object.freeze([
  { value: 'WEEK', label: '本周' },
  { value: 'MONTH', label: '本月' },
  { value: 'YEAR', label: '本年' }
]);
const days = computed(() => Array.isArray(props.points) ? props.points : []);
const periodTitle = computed(() => trendPeriodPresentation(props.period).title);
const chartOption = computed(() => overviewTrendChartOption(days.value, props.period));

function handleChartClick(params) {
  const point = days.value[params?.dataIndex];
  if (!point) return;
  emit('navigate', {
    actionRoute: 'backup',
    actionQuery: { finishedFrom: point.date, finishedTo: trendPointEnd(point.date, props.period) }
  });
}
</script>

<template>
  <el-card v-loading="loading" shadow="never">
    <template #header>
      <el-row justify="space-between" align="middle">
        <el-text tag="strong">{{ periodTitle }}</el-text>
        <el-segmented
          :model-value="period"
          :options="periodOptions"
          aria-label="备份趋势时间周期"
          @change="$emit('period-change', $event)"
        />
      </el-row>
    </template>
    <el-alert v-if="error" type="warning" :closable="false" show-icon :title="error.message || '趋势数据加载失败'" />
    <el-empty v-if="!days.length" description="当前周期暂无趋势数据" :image-size="72" />
    <v-chart
      v-else
      :option="chartOption"
      :init-options="{ renderer: 'canvas' }"
      style="height: 390px"
      autoresize
      @click="handleChartClick"
    />
  </el-card>
</template>
