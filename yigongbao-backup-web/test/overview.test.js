import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, existsSync } from 'node:fs';
import { compile } from '@vue/compiler-dom';
import { parse } from '@vue/compiler-sfc';
import { renderToString } from '@vue/server-renderer';
import ElementPlus from 'element-plus';
import { createServer } from 'vite';
import * as Vue from 'vue';
import * as overviewModel from '../src/overviewModel.js';

const {
  activityPresentation, dateKeyOffset, formatBytes, formatCount, formatPercent, formatTime, healthPresentation,
  normalizeTrend, orderedAlerts, overviewDrilldown, overviewTrendUrl, trendPeriodPresentation,
  trendPointEnd, trendTickLabel
} = overviewModel;

const read = path => readFileSync(new URL(path, import.meta.url), 'utf8');

function compileVueTemplate(path) {
  const source = read(path);
  const { descriptor } = parse(source);
  const { code } = compile(descriptor.template.content, { mode:'function' });
  return new Function('Vue', code)(Vue);
}

function createTemplateStub() {
  return Vue.defineComponent({
    inheritAttrs:false,
    setup(_props, { attrs }) {
      return () => Vue.h('section', attrs);
    }
  });
}

async function renderComponentTemplate(path, bindings, componentNames = []) {
  const render = compileVueTemplate(path);
  const app = Vue.createSSRApp({ setup:() => bindings, render });
  app.config.warnHandler = () => {};
  app.use(ElementPlus);
  const stub = createTemplateStub();
  for (const name of componentNames) app.component(name, stub);
  return renderToString(app);
}

async function createSfcRenderer() {
  const server = await createServer({ server:{ middlewareMode:true }, appType:'custom', logLevel:'silent' });
  return {
    async render(path, props = {}, componentNames = []) {
      const module = await server.ssrLoadModule(path);
      const app = Vue.createSSRApp({ render:() => Vue.h(module.default, props) });
      app.config.warnHandler = () => {};
      app.use(ElementPlus);
      const stub = createTemplateStub();
      for (const name of componentNames) app.component(name, stub);
      return renderToString(app);
    },
    close:() => server.close()
  };
}

async function renderOverviewTemplate() {
  const overview = {
    health:{}, observedAt:'2026-11-12T16:00:00', activeAlerts:[], summary:{}, dataQuality:{ messages:[] },
    backupTrend:[], automation:{}, storage:{}, recentActivities:[], sectionErrors:[], sections:{}
  };
  return renderComponentTemplate('../src/views/OverviewView.vue', {
    overview, overviewLoading:false, overviewError:null, overviewTrendPeriod:'WEEK',
    overviewTrendLoading:false, overviewTrendError:null, loadOverview() {}, loadOverviewTrend() {},
    drill() {}, dismissOverviewAlert() {}, unavailable:() => false
  }, ['OverviewHealthHero','OverviewAlerts','OverviewMetrics','OverviewTrend','OverviewStorage','OverviewAutomation','OverviewActivity']);
}

test('overview formatters preserve the difference between zero and unknown', () => {
  assert.equal(formatCount(0), '0');
  assert.equal(formatCount(null), '—');
  assert.equal(formatBytes(0), '0 B');
  assert.equal(formatBytes(1073741824), '1 GB');
  assert.equal(formatPercent(0), '0.0%');
  assert.equal(formatPercent(null), '—');
  assert.match(formatTime('2026-11-11T00:05:00'), /2026.*11.*11.*00:05:00/);
});

test('overview presents all health levels without deriving them from counters', () => {
  assert.equal(healthPresentation('NORMAL').label, '运行正常');
  assert.equal(healthPresentation('WARNING').tone, 'warning');
  assert.equal(healthPresentation('CRITICAL').tone, 'danger');
  assert.equal(healthPresentation('DEGRADED').label, '部分数据不可用');
  assert.equal(healthPresentation('future').label, '状态未知');
});

test('overview alert dismissal endpoint safely encodes the stable alert key', () => {
  assert.equal(overviewModel.overviewAlertDismissUrl('CODE:ORDER:1'),
    '/admin/overview/alerts/CODE%3AORDER%3A1/dismiss');
  assert.equal(overviewModel.overviewAlertDismissUrl(''), null);
});

test('trend fills seven days and alert ordering is stable', () => {
  const points = normalizeTrend([{ date: '2026-11-09', completeSucceeded: 3 }], new Date('2026-11-11T12:00:00+08:00'));
  assert.equal(points.length, 7);
  assert.equal(points.find(x => x.date === '2026-11-09').completeSucceeded, 3);
  assert.equal(points.find(x => x.date === '2026-11-08').completeSucceeded, 0);
  assert.equal(dateKeyOffset('2026-11-11', 1), '2026-11-12');
  assert.equal(normalizeTrend([], '2026-11-11T00:05:00').at(-1).date, '2026-11-11');
  const alerts = orderedAlerts([{ alertKey:'w', severity:'WARNING', evidenceAt:'2026-11-11' }, { alertKey:'c', severity:'CRITICAL', evidenceAt:'2026-11-10' }]);
  assert.deepEqual(alerts.map(x => x.alertKey), ['c', 'w']);
});

test('trend periods expose calendar labels, endpoint parameters and drilldown ranges', () => {
  assert.deepEqual(trendPeriodPresentation('WEEK'), { label:'本周', title:'本周备份走势' });
  assert.deepEqual(trendPeriodPresentation('MONTH'), { label:'本月', title:'本月备份走势' });
  assert.deepEqual(trendPeriodPresentation('YEAR'), { label:'本年', title:'本年备份走势' });
  assert.equal(overviewTrendUrl('MONTH'), '/admin/overview/trend?period=MONTH');
  assert.equal(trendPointEnd('2026-11-12', 'WEEK'), '2026-11-13');
  assert.equal(trendPointEnd('2026-11-12', 'MONTH'), '2026-11-13');
  assert.equal(trendPointEnd('2026-11-01', 'YEAR'), '2026-12-01');
  assert.equal(trendTickLabel('2026-11-01', 'MONTH', 0, 30), '11-01');
  assert.equal(trendTickLabel('2026-11-02', 'MONTH', 1, 30), '');
  assert.equal(trendTickLabel('2026-11-05', 'MONTH', 4, 30), '11-05');
  assert.equal(trendTickLabel('2026-12-01', 'YEAR', 11, 12), '12月');
});

test('overview trend chart keeps every period point in synchronized ECharts grids', () => {
  const points = [
    { date:'2026-11-01', completeSucceeded:3, incompleteSucceeded:1, failed:0, skipped:2, addedPackageBytes:1024 },
    { date:'2026-11-02', completeSucceeded:4, incompleteSucceeded:0, failed:1, skipped:0, addedPackageBytes:2048 }
  ];
  const option = overviewModel.overviewTrendChartOption(points, 'MONTH');
  assert.deepEqual(option.xAxis[0].data, ['11-01', '11-02']);
  assert.deepEqual(option.xAxis[1].data, ['11-01', '11-02']);
  assert.deepEqual(option.series.map(series => series.type), ['line', 'line', 'line', 'line', 'bar']);
  assert.deepEqual(option.series[0].data, [3, 4]);
  assert.deepEqual(option.series[4].data, [1024, 2048]);
  assert.equal(option.dataZoom[0].xAxisIndex.length, 2);
  assert.equal(option.aria.enabled, true);
  assert.match(option.tooltip.formatter([{ axisValue:'11-02', seriesName:'新增订单包容量', value:2048, marker:'' }]), /2 KB/);
});

test('monthly ECharts axis samples labels without dropping daily points', () => {
  const points = Array.from({ length: 6 }, (_, index) => ({
    date: `2026-11-${String(index + 1).padStart(2, '0')}`,
    completeSucceeded: index + 1
  }));
  const option = overviewModel.overviewTrendChartOption(points, 'MONTH');
  const formatter = option.xAxis[0].axisLabel.formatter;
  assert.equal(option.series[0].data.length, 6);
  assert.equal(formatter('11-01', 0), '11-01');
  assert.equal(formatter('11-02', 1), '');
  assert.equal(formatter('11-05', 4), '11-05');
  assert.equal(formatter('11-06', 5), '11-06');
});

test('overview status values map to Element Plus tag semantics', () => {
  assert.equal(overviewModel.elementTagType('SUCCESS'), 'success');
  assert.equal(overviewModel.elementTagType('RUNNING'), 'primary');
  assert.equal(overviewModel.elementTagType('WARNING'), 'warning');
  assert.equal(overviewModel.elementTagType('FAILED'), 'danger');
  assert.equal(overviewModel.elementTagType('NOT_RUN'), 'info');
  assert.equal(overviewModel.elementResultIcon('danger'), 'error');
  assert.equal(overviewModel.elementResultIcon('success'), 'success');
});

test('unknown activity stays read-only and drilldowns only allow controlled pages', () => {
  assert.equal(activityPresentation({ activityType:'FUTURE', actionRoute:'backup' }).interactive, false);
  assert.equal(activityPresentation({ activityType:'ORDER_BACKUP', actionRoute:'backup' }).interactive, true);
  const cloudCleanup = activityPresentation({ activityType:'AUTO_CLEANUP', actionRoute:'cleanup' });
  assert.equal(cloudCleanup.interactive, true);
  assert.equal(cloudCleanup.typeLabel, '云端清理');
  assert.deepEqual(overviewDrilldown({ actionRoute:'orders', actionQuery:{ backupMode:'REAL' } }), { target:'orders', query:{ backupMode:'REAL' } });
  assert.equal(overviewDrilldown({ actionRoute:'javascript:alert(1)' }), null);
});

test('route store encodes whitelisted query and consumes it exactly once', async () => {
  const state = new Map();
  globalThis.localStorage = { getItem:key => state.get(key) || null, setItem:(key,value) => state.set(key,value), removeItem:key => state.delete(key) };
  globalThis.window = { location:{ hash:'#orders?backupMode=REAL&evil=1' }, history:{ replaceState(_a,_b,url){ window.location.hash = url; } } };
  const { createRouteStore } = await import('../src/stores/routeStore.js');
  const route = createRouteStore();
  assert.deepEqual(route.routeQuery.value, { backupMode:'REAL' });
  route.navigate('backup', { jobId:'12', ignored:'x', orderResult:'FAILED' });
  assert.equal(window.location.hash, '#backup?jobId=12&orderResult=FAILED');
  assert.deepEqual(route.consumeRouteQuery(['jobId']), { jobId:'12' });
  assert.deepEqual(route.consumeRouteQuery(['jobId']), {});
  route.navigate('javascript:alert(1)', { jobId:'1' });
  assert.equal(route.page.value, 'orders');
  assert.equal(window.location.hash, '#orders');
});

test('overview page is composed and never falls back to raw JSON or a fixed success claim', () => {
  const page = read('../src/views/OverviewView.vue');
  for (const component of ['OverviewHealthHero','OverviewAlerts','OverviewMetrics','OverviewTrend','OverviewStorage','OverviewAutomation','OverviewActivity']) assert.match(page, new RegExp(component));
  assert.doesNotMatch(page, /JSON\.stringify|服务正常|运行任务|覆盖率/);
  assert.match(page, /requestId/);
  for (const name of ['OverviewHealthHero','OverviewAlerts','OverviewMetrics','OverviewTrend','OverviewStorage','OverviewAutomation','OverviewActivity']) {
    assert.equal(existsSync(new URL(`../src/components/overview/${name}.vue`, import.meta.url)), true);
  }
});

test('overview first screen uses Element Plus layout and controls without handwritten UI primitives', () => {
  const page = read('../src/views/OverviewView.vue');
  const hero = read('../src/components/overview/OverviewHealthHero.vue');
  const metrics = read('../src/components/overview/OverviewMetrics.vue');
  const automation = read('../src/components/overview/OverviewAutomation.vue');
  const style = read('../src/style-production.css');
  assert.match(page, /<OverviewHealthHero[^>]+:health="overview\.health"[^>]+:alerts="overview\.activeAlerts"[^>]+@navigate="drill"[^>]+@dismiss="dismissOverviewAlert"/s);
  assert.match(page, /:refreshing="overviewLoading"/);
  assert.match(page, /@refresh="loadOverview\(\{ refresh: true \}\)"/);
  assert.match(page, /<el-alert v-if="overviewError"/);
  assert.match(page, /overviewError\.code/);
  assert.match(page, /overviewError\.requestId/);
  assert.match(page, /<el-container direction="vertical">\s*<el-space/s);
  assert.match(page, /<el-row/);
  assert.match(page, /<el-col/);
  assert.match(hero, /<el-card/);
  assert.match(hero, /<el-result/);
  assert.match(hero, /<el-statistic/);
  assert.match(hero, /aria-label="刷新数据"/);
  assert.doesNotMatch(hero, />刷新数据</);
  assert.match(metrics, /<el-statistic/);
  assert.match(metrics, /<el-descriptions/);
  assert.match(automation, /<el-table/);
  for (const source of [page, hero, metrics, automation]) {
    assert.doesNotMatch(source, /class="overview-/);
    assert.doesNotMatch(source, /<(button|article|section|figure)\b/);
  }
  assert.doesNotMatch(style, /\.overview-/);
});

test('overview exposes every alert after the hero item and refreshes after any in-flight request before closing', () => {
  const alerts = read('../src/components/overview/OverviewAlerts.vue');
  const source = read('../src/composables/useBackupConsole.js');
  assert.match(alerts, /orderedAlerts\(props\.alerts\)\.slice\(1\)/);
  assert.doesNotMatch(alerts, /slice\(1,\s*3\)/);
  assert.match(alerts, /<el-table[^>]+:max-height="360"/s);
  assert.match(source, /if \(overviewRequest\) await overviewRequest;\s*await loadOverview\(\{ refresh:true \}\)/s);
});

test('overview alert panels expose an explicit manual close action', async () => {
  const renderer = await createSfcRenderer();
  const alerts = [
    { alertKey:'one', severity:'CRITICAL', title:'严重告警', message:'说明', evidenceAt:'2026-11-12T16:00:00' },
    { alertKey:'two', severity:'WARNING', title:'警告一', message:'说明', evidenceAt:'2026-11-12T15:00:00' },
    { alertKey:'three', severity:'WARNING', title:'警告二', message:'说明', evidenceAt:'2026-11-12T14:00:00' }
  ];
  try {
    const hero = await renderer.render('/src/components/overview/OverviewHealthHero.vue', {
      health:{ level:'CRITICAL', activeAlertCount:3, criticalAlertCount:1, warningAlertCount:2 },
      observedAt:'2026-11-12T16:00:00', alerts, error:null, refreshing:false
    }, ['Refresh','ArrowRight','Close']);
    assert.match(hero, />\s*关闭告警\s*</);
    const list = read('../src/components/overview/OverviewAlerts.vue');
    assert.match(list, /@click="\$emit\('dismiss', row\)"[^>]*>关闭<\/el-button>/);
  } finally {
    await renderer.close();
  }
});

test('overview health result is compact and summary values use stronger visual hierarchy', async () => {
  const renderer = await createSfcRenderer();
  try {
    const hero = await renderer.render('/src/components/overview/OverviewHealthHero.vue', {
      health:{ level:'NORMAL', activeAlertCount:0, criticalAlertCount:0, warningAlertCount:0 },
      observedAt:'2026-11-12T16:00:00', alerts:[], error:null, refreshing:false
    }, ['Refresh','ArrowRight']);
    const compactResults = hero.match(/class="el-result" style="[^"]*--el-result-padding:12px 16px[^"]*--el-result-icon-font-size:48px[^"]*"/g) || [];
    assert.equal(compactResults.length, 2);
    const emphasizedAlertCount = hero.match(/class="el-statistic__number" style="[^"]*font-size:32px[^"]*font-weight:700[^"]*"/g) || [];
    assert.equal(emphasizedAlertCount.length, 1);

    const metrics = await renderer.render('/src/components/overview/OverviewMetrics.vue', {
      summary:{
        orderStatistics:{ total:10, hot:10, cold:0 },
        readableFiles:{ orderPackageCount:10, logicalFileCount:167, managedArtifactBytes:2555505541, metadataRecordCount:13 },
        todayBackup:{ total:10, completeSucceeded:10, incompleteSucceeded:0, failed:0 }
      }
    }, ['ArrowRight']);
    const emphasizedValues = metrics.match(/class="el-statistic__number" style="[^"]*font-size:32px[^"]*font-weight:700[^"]*"/g) || [];
    assert.equal(emphasizedValues.length, 3);
    assert.match(metrics, /订单包[\s\S]*10/);
    assert.match(metrics, /文件明细[\s\S]*167/);
    assert.doesNotMatch(metrics, /el-descriptions--small/);
  } finally {
    await renderer.close();
  }
});

test('overview keeps a visible Element Plus space between both stacked panel pairs', () => {
  const page = read('../src/views/OverviewView.vue');
  const style = read('../src/style-production.css');
  const contentRule = style.match(/\.console-content\s*\{([^}]*)\}/)?.[1] || '';
  assert.doesNotMatch(contentRule, /max-width/);
  assert.doesNotMatch(contentRule, /margin\s*:/);
  assert.match(
    page,
    /<el-col :xs="24" :md="16">\s*<el-space direction="vertical" fill :size="16"[^>]*>[\s\S]*?<OverviewTrend[\s\S]*?<OverviewAutomation[\s\S]*?<\/el-space>\s*<\/el-col>/
  );
  assert.match(
    page,
    /<el-col :xs="24" :md="8">\s*<el-space direction="vertical" fill :size="16"[^>]*>[\s\S]*?<OverviewStorage[\s\S]*?<OverviewActivity[\s\S]*?<\/el-space>\s*<\/el-col>/
  );
  assert.doesNotMatch(page, /tag="el-space"/);
});

test('overview lower panels render on the same three-column grid as the summary cards', async () => {
  const html = await renderOverviewTemplate();
  assert.match(html, /class="[^"]*\bel-col-md-16\b[^"]*"/);
  assert.match(html, /class="[^"]*\bel-col-md-8\b[^"]*"/);
  const fullWidthColumnStacks = html.match(/class="el-space el-space--vertical" style="[^"]*width:100%[^"]*"/g) || [];
  assert.equal(fullWidthColumnStacks.length >= 2, true);
});

test('recent activity aligns its fixed viewport with automation and keeps each row compact', () => {
  const activity = read('../src/components/overview/OverviewActivity.vue');
  assert.match(activity, /<el-scrollbar[^>]+height="452px"/);
  assert.match(activity, /<el-timeline>/);
  assert.match(activity, /<el-timeline-item/);
  assert.match(activity, /<el-container direction="vertical">\s*<el-space direction="vertical" fill/);
  assert.match(
    activity,
    /<el-space wrap :size="6">[\s\S]*?<el-text tag="strong"[\s\S]*?<el-tag size="small" :type="elementTagType\(item\.status\)">\{\{ statusLabel\(item\.status\) \}\}<\/el-tag>/
  );
  assert.equal((activity.match(/activityPresentation\(item\)\.typeLabel/g) || []).length, 1);
  assert.match(activity, /<el-col :span="8"><el-text size="small">完整 \{\{/);
  assert.match(activity, /<el-col :span="8"><el-text size="small">降级 \{\{/);
  assert.match(activity, /<el-col :span="8"><el-text size="small">失败 \{\{/);
  assert.doesNotMatch(activity, /<el-row :gutter="8"/);
  assert.equal((activity.match(/<el-card\b/g) || []).length, 1);
  assert.doesNotMatch(activity, /<el-descriptions/);
  assert.doesNotMatch(activity, /class="activity-/);
});

test('automation separates schedule timing from the task description', () => {
  const automation = read('../src/components/overview/OverviewAutomation.vue');
  assert.match(automation, /<el-table-column label="调度状态"/);
  assert.match(automation, /statusLabel\(row\.scheduleStatus\)/);
  assert.match(automation, /<el-table-column label="最近一次执行状态"/);
  assert.match(automation, /statusLabel\(row\.lastRunStatus\)/);
  assert.doesNotMatch(automation, /<el-table-column label="状态"/);
  assert.match(automation, /<el-table-column label="调度时间"/);
  assert.match(automation, />周期：\{\{ schedulePeriodText\(row\) \}\}</);
  assert.match(automation, />最近成功：\{\{ formatTime\(row\.lastSuccessAt\) \}\}</);
  assert.match(automation, />下次预期：\{\{ formatTime\(row\.nextExpectedAt\) \}\}</);
  assert.doesNotMatch(automation, /const scheduleText/);
});

test('trend component uses Element Plus segmented control and ECharts', () => {
  const trend = read('../src/components/overview/OverviewTrend.vue');
  assert.match(trend, /<el-segmented/);
  assert.match(trend, /<v-chart/);
  assert.match(trend, /<el-empty v-if="!days\.length"/);
  assert.match(trend, /overviewTrendChartOption/);
  assert.match(trend, /@change="\$emit\('period-change',\s*\$event\)"/);
  assert.doesNotMatch(trend, /overview-bars|overview-capacity-bars|<figure/);
  assert.doesNotMatch(trend, /normalizeTrend/);
  assert.match(trend, /trendPointEnd/);
  const consoleSource = read('../src/composables/useBackupConsole.js');
  assert.match(consoleSource, /let lastSuccessfulTrendPeriod = 'WEEK'/);
  assert.match(consoleSource, /overviewTrendPeriod\.value = lastSuccessfulTrendPeriod/);
});

test('overview storage shows remaining capacity without overemphasizing a dashboard gauge', () => {
  const storage = read('../src/components/overview/OverviewStorage.vue');
  assert.match(storage, /剩余/);
  assert.doesNotMatch(storage, /type="dashboard"/);
});

test('overview summary presents backed-up order tiers and real asset totals', () => {
  const metrics = read('../src/components/overview/OverviewMetrics.vue');
  assert.match(metrics, />订单统计</);
  assert.match(metrics, /orderStatistics\?\.total/);
  assert.match(metrics, /orderStatistics\?\.hot/);
  assert.match(metrics, /orderStatistics\?\.cold/);
  assert.match(metrics, /metadataRecordCount/);
  assert.doesNotMatch(metrics, /文件数未知|容量未知|元数据待补全/);
});

test('overview automation and capacity helpers present operator-facing values', async () => {
  const model = await import('../src/overviewModel.js');
  assert.equal(typeof model.executionModeLabel, 'function');
  assert.equal(typeof model.formatInterval, 'function');
  assert.equal(typeof model.remainingCapacity, 'function');
  assert.equal(model.executionModeLabel('REAL'), '正式备份');
  assert.equal(model.executionModeLabel('SIMULATION'), '模拟备份');
  assert.equal(model.formatInterval(1, 'DAYS'), '每天');
  assert.equal(model.formatInterval(30, 'MINUTES'), '每 30 分钟');
  assert.equal(model.formatInterval(null, null), '—');
  assert.equal(model.remainingCapacity(1000, 600), 400);
  assert.equal(model.remainingCapacity(null, 600), null);
  assert.equal(model.remainingCapacity(100, 120), 0);
  assert.equal(model.currentColdMediaCount({ totalCount: 1, successfulArchiveCount: 2 }), 1);
  assert.equal(model.currentColdMediaCount({ successfulArchiveCount: 2 }), null);
  assert.equal(typeof model.automationRows, 'function');
  const automation = model.automationRows({
    backup: { enabled:true, state:'NORMAL', scheduleState:'READY', lastRunStatus:'SUCCEEDED' },
    accountSync: { enabled:true, state:'NORMAL', scheduleState:'READY', lastRunStatus:'SUCCEEDED' },
    cleanup: { enabled:true, state:'NORMAL', scheduleState:'READY', lastRunStatus:'SUCCEEDED' }
  });
  assert.deepEqual(automation.map(item => [item.key,item.label,item.state,item.scheduleStatus,item.lastRunStatus]), [
    ['backup','自动备份','NORMAL','NORMAL','SUCCEEDED'],
    ['accountSync','账号同步','NORMAL','NORMAL','SUCCEEDED'],
    ['cleanup','云端清理','NORMAL','NORMAL','SUCCEEDED']
  ]);
  const legacyReady = model.automationRows({
    backup: { enabled:true, state:'READY', lastSuccessAt:'2026-11-12T09:29:07' },
    accountSync: { enabled:true, state:'READY' }
  });
  assert.equal(legacyReady[0].state, 'NORMAL');
  assert.equal(legacyReady[0].lastRunStatus, 'SUCCEEDED');
  assert.equal(legacyReady[1].state, 'WAITING');
  assert.equal(legacyReady[1].lastRunStatus, 'NOT_RUN');
});

test('overview state retains data on refresh error and route consumers no longer use session storage', () => {
  const source = read('../src/composables/useBackupConsole.js');
  const restore = read('../src/views/RestoreView.vue');
  assert.match(source, /overviewError\.value/);
  assert.match(source, /overviewLastSuccessAt\.value/);
  assert.match(source, /if \(overviewRequest\) return overviewRequest/);
  assert.doesNotMatch(source + restore, /backup-restore-task-focus/);
  assert.match(restore, /consumeRouteQuery\(\['jobId'\]\)/);
});
