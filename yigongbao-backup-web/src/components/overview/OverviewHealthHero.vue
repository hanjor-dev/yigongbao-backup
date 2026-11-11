<script setup>
import { computed } from 'vue';
import { formatTime, healthPresentation } from '../../overviewModel.js';
const props = defineProps({ health: Object, observedAt: String, loading: Boolean, error: Object, lastSuccessAt: String, dataQuality: Object });
defineEmits(['refresh']);
const state = computed(() => healthPresentation(props.health?.level));
</script>
<template>
  <section class="overview-hero" :class="`is-${state.tone}`" aria-live="polite">
    <div class="overview-hero__signal"><span class="overview-pulse" aria-hidden="true"></span><span>系统态势</span></div>
    <div class="overview-hero__main"><div><p class="overview-eyebrow">当前健康结论</p><h2>{{ state.label }}</h2><p>观测时间 {{ formatTime(observedAt) }}<template v-if="lastSuccessAt"> · 前端更新 {{ formatTime(lastSuccessAt) }}</template></p></div>
      <div class="overview-health-counts"><strong>{{ health?.activeAlertCount ?? '—' }}</strong><span>当前告警</span><small>严重 {{ health?.criticalAlertCount ?? '—' }} · 警告 {{ health?.warningAlertCount ?? '—' }}</small></div></div>
    <el-alert v-if="error" class="overview-refresh-error" type="warning" :closable="false" show-icon>
      <template #title>刷新失败，正在展示上次成功数据</template>
      {{ error.message }}<span v-if="error.code"> · {{ error.code }}</span><span v-if="error.requestId"> · requestId {{ error.requestId }}</span>
    </el-alert>
    <div class="overview-hero__footer"><span v-if="dataQuality && !dataQuality.complete">部分统计数据不可用，请结合分区提示判断。</span><span v-else>关键统计已完成本次聚合。</span><el-button :loading="loading" type="primary" @click="$emit('refresh')"><el-icon><Refresh /></el-icon>刷新态势</el-button></div>
  </section>
</template>
