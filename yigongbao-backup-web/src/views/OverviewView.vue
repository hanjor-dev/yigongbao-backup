<script setup>
import { computed } from 'vue';
import { useBackupContext } from '../composables/useBackupContext.js';
import { overviewDrilldown } from '../overviewModel.js';
import OverviewHealthHero from '../components/overview/OverviewHealthHero.vue';
import OverviewAlerts from '../components/overview/OverviewAlerts.vue';
import OverviewMetrics from '../components/overview/OverviewMetrics.vue';
import OverviewTrend from '../components/overview/OverviewTrend.vue';
import OverviewStorage from '../components/overview/OverviewStorage.vue';
import OverviewAutomation from '../components/overview/OverviewAutomation.vue';
import OverviewActivity from '../components/overview/OverviewActivity.vue';

const { overview, overviewLoading, overviewError, overviewLastSuccessAt, loadOverview, navigate } = useBackupContext();
const sections = computed(() => overview.value?.sections || {});
const unavailable = key => sections.value[key] === 'UNAVAILABLE';
function drill(item) {
  const target = overviewDrilldown(item);
  if (target) navigate(target.target, target.query);
}
</script>
<template><div class="page-stack overview-page">
  <section class="page-heading"><div><h2>管理概览</h2><p>用同一口径查看备份资产、存储可用性、自动化与当前风险。</p></div></section>
  <OverviewHealthHero :health="overview?.health" :observed-at="overview?.observedAt" :loading="overviewLoading" :error="overviewError" :last-success-at="overviewLastSuccessAt" :data-quality="overview?.dataQuality" @refresh="loadOverview({ refresh: true })" />
  <template v-if="overview">
    <OverviewAlerts v-if="!unavailable('activeAlerts')" :alerts="overview.activeAlerts" @navigate="drill" />
    <el-alert v-else type="warning" :closable="false" show-icon title="告警分区本次加载失败" />
    <OverviewMetrics v-if="!unavailable('summary')" :summary="overview.summary" @navigate="drill" />
    <el-alert v-else type="warning" :closable="false" show-icon title="核心指标本次加载失败" />
    <el-alert v-for="message in overview.dataQuality?.messages" :key="message.code" :type="message.severity === 'BLOCKING' ? 'error' : 'warning'" :closable="false" show-icon><template #title>数据质量提示 · {{ message.section }}</template>{{ message.message }}（影响 {{ message.affectedCount }} 条）</el-alert>
    <div class="overview-two-column"><OverviewTrend v-if="!unavailable('backupTrend')" :points="overview.backupTrend" :observed-at="overview.observedAt" @navigate="drill" /><OverviewStorage v-if="!unavailable('storage')" :storage="overview.storage" @navigate="drill" /></div>
    <div class="overview-two-column overview-two-column--lower"><OverviewAutomation v-if="!unavailable('automation')" :automation="overview.automation" @navigate="drill" /><OverviewActivity v-if="!unavailable('recentActivities')" :activities="overview.recentActivities" @navigate="drill" /></div>
    <el-alert v-for="error in overview.sectionErrors" :key="`${error.section}-${error.code}`" type="warning" :closable="false" show-icon><template #title>{{ error.section }} 数据暂不可用</template>{{ error.message }}<span v-if="error.requestId"> · requestId {{ error.requestId }}</span></el-alert>
  </template>
  <el-skeleton v-else :loading="overviewLoading" animated :rows="10"><template #default><el-empty description="暂无概览数据"><el-button type="primary" @click="loadOverview({ refresh: true })">重新加载</el-button></el-empty></template></el-skeleton>
</div></template>
