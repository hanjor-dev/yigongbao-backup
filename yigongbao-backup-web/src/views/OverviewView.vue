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

const {
  overview,
  overviewLoading,
  overviewError,
  overviewTrendPeriod,
  overviewTrendLoading,
  overviewTrendError,
  loadOverview,
  loadOverviewTrend,
  dismissOverviewAlert,
  navigate
} = useBackupContext();
const sections = computed(() => overview.value?.sections || {});
const unavailable = key => sections.value[key] === 'UNAVAILABLE';

function drill(item) {
  const target = overviewDrilldown(item);
  if (target) navigate(target.target, target.query);
}
</script>

<template>
  <el-container direction="vertical">
    <el-space direction="vertical" fill :size="16">
    <OverviewHealthHero
      v-if="overview"
      :health="overview.health"
      :observed-at="overview.observedAt"
      :alerts="overview.activeAlerts"
      :error="overviewError"
      :refreshing="overviewLoading"
      @refresh="loadOverview({ refresh: true })"
      @navigate="drill"
      @dismiss="dismissOverviewAlert"
    />

    <template v-if="overview">
      <OverviewAlerts
        v-if="!unavailable('activeAlerts') && overview.activeAlerts?.length > 1"
        :alerts="overview.activeAlerts"
        @navigate="drill"
        @dismiss="dismissOverviewAlert"
      />
      <el-alert v-if="unavailable('activeAlerts')" type="warning" :closable="false" show-icon title="告警分区本次加载失败" />

      <OverviewMetrics v-if="!unavailable('summary')" :summary="overview.summary" @navigate="drill" />
      <el-alert v-else type="warning" :closable="false" show-icon title="核心指标本次加载失败" />

      <el-alert
        v-for="message in overview.dataQuality?.messages"
        :key="message.code"
        :type="message.severity === 'BLOCKING' ? 'error' : 'warning'"
        :closable="false"
        show-icon
      >
        <template #title>数据质量提示 · {{ message.section }}</template>
        {{ message.message }}（影响 {{ message.affectedCount }} 条）
      </el-alert>

      <el-row :gutter="16">
        <el-col :xs="24" :md="16">
          <el-space direction="vertical" fill :size="16" :style="{ width: '100%' }">
            <OverviewTrend
              v-if="!unavailable('backupTrend')"
              :points="overview.backupTrend"
              :observed-at="overview.observedAt"
              :period="overviewTrendPeriod"
              :loading="overviewTrendLoading"
              :error="overviewTrendError"
              @period-change="loadOverviewTrend"
              @navigate="drill"
            />
            <OverviewAutomation v-if="!unavailable('automation')" :automation="overview.automation" @navigate="drill" />
          </el-space>
        </el-col>
        <el-col :xs="24" :md="8">
          <el-space direction="vertical" fill :size="16" :style="{ width: '100%' }">
            <OverviewStorage v-if="!unavailable('storage')" :storage="overview.storage" @navigate="drill" />
            <OverviewActivity v-if="!unavailable('recentActivities')" :activities="overview.recentActivities" @navigate="drill" />
          </el-space>
        </el-col>
      </el-row>

      <el-alert
        v-for="error in overview.sectionErrors"
        :key="`${error.section}-${error.code}`"
        type="warning"
        :closable="false"
        show-icon
      >
        <template #title>{{ error.section }} 数据暂不可用</template>
        {{ error.message }}<span v-if="error.requestId"> · requestId {{ error.requestId }}</span>
      </el-alert>
    </template>

    <el-card v-else shadow="never">
      <el-skeleton :loading="overviewLoading" animated :rows="10">
        <template #default>
          <el-alert v-if="overviewError" type="error" :closable="false" show-icon>
            <template #title>{{ overviewError.message || '概览数据加载失败' }}</template>
            <el-space wrap :size="8">
              <el-tag v-if="overviewError.code" type="danger" effect="plain">错误码 {{ overviewError.code }}</el-tag>
              <el-tag v-if="overviewError.requestId" type="info" effect="plain">requestId {{ overviewError.requestId }}</el-tag>
            </el-space>
          </el-alert>
          <el-empty description="暂无概览数据">
            <el-button type="primary" @click="loadOverview({ refresh: true })">重新加载</el-button>
          </el-empty>
        </template>
      </el-skeleton>
    </el-card>
    </el-space>
  </el-container>
</template>
