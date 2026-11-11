<script setup>
import { computed } from 'vue';
import { dateKeyOffset, formatBytes, normalizeTrend } from '../../overviewModel.js';
const props = defineProps({ points: Array, observedAt: String });
defineEmits(['navigate']);
const days = computed(() => normalizeTrend(props.points, props.observedAt || new Date()));
const resultMax = computed(() => Math.max(1, ...days.value.map(x => x.completeSucceeded + x.incompleteSucceeded + x.failed + x.skipped)));
const bytesMax = computed(() => Math.max(1, ...days.value.map(x => x.addedPackageBytes || 0)));
const h = (value, max) => `${Math.max(value ? 6 : 1, Math.round(Number(value || 0) * 76 / max))}px`;
</script>
<template><el-card shadow="never" class="overview-panel overview-trend"><template #header><div class="overview-panel__title"><div><span>近 7 日备份走势</span><small>订单结果与新增订单包容量分别计量</small></div></div></template>
  <div class="overview-charts"><figure><figcaption>订单结果</figcaption><div class="overview-bars" role="img" aria-label="近七日订单备份结果柱状图"><button v-for="day in days" :key="day.date" type="button" :title="`${day.date}：完整 ${day.completeSucceeded}，降级 ${day.incompleteSucceeded}，失败 ${day.failed}，跳过 ${day.skipped}`" @click="$emit('navigate', { actionRoute: 'backup', actionQuery: { finishedFrom: day.date, finishedTo: dateKeyOffset(day.date, 1) } })"><span class="bar is-complete" :style="{height:h(day.completeSucceeded,resultMax)}"></span><span class="bar is-incomplete" :style="{height:h(day.incompleteSucceeded,resultMax)}"></span><span class="bar is-failed" :style="{height:h(day.failed,resultMax)}"></span><small>{{ day.date.slice(5) }}</small></button></div><p class="chart-legend"><i class="is-complete"></i>完整成功 <i class="is-incomplete"></i>降级成功 <i class="is-failed"></i>失败</p></figure>
    <figure><figcaption>新增订单包容量</figcaption><div class="overview-capacity-bars" role="img" aria-label="近七日新增订单包容量柱状图"><div v-for="day in days" :key="day.date" :title="`${day.date}：${formatBytes(day.addedPackageBytes)}`"><span :style="{height:h(day.addedPackageBytes,bytesMax)}"></span><small>{{ day.date.slice(5) }}</small></div></div><p class="chart-legend">峰值 {{ formatBytes(bytesMax) }}<template v-if="days.some(x=>x.unknownPackageCount)"> · 存在容量未知的订单包</template></p></figure></div>
</el-card></template>
