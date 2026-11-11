import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, existsSync } from 'node:fs';
import {
  activityPresentation, dateKeyOffset, formatBytes, formatCount, formatPercent, formatTime, healthPresentation,
  normalizeTrend, orderedAlerts, overviewDrilldown
} from '../src/overviewModel.js';

const read = path => readFileSync(new URL(path, import.meta.url), 'utf8');

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

test('unknown activity stays read-only and drilldowns only allow controlled pages', () => {
  assert.equal(activityPresentation({ activityType:'FUTURE', actionRoute:'backup' }).interactive, false);
  assert.equal(activityPresentation({ activityType:'ORDER_BACKUP', actionRoute:'backup' }).interactive, true);
  assert.equal(activityPresentation({ activityType:'AUTO_CLEANUP', actionRoute:'cleanup' }).interactive, true);
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
  assert.match(page, /overviewLastSuccessAt/);
  for (const name of ['OverviewHealthHero','OverviewAlerts','OverviewMetrics','OverviewTrend','OverviewStorage','OverviewAutomation','OverviewActivity']) {
    assert.equal(existsSync(new URL(`../src/components/overview/${name}.vue`, import.meta.url)), true);
  }
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
