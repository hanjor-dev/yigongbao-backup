import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { navigationGroupsForRole } from '../src/stores/routeStore.js';

const read = (path) => readFileSync(new URL(path, import.meta.url), 'utf8');

test('admin backup page renders automatic backup tasks without candidate selection or task-center menu', () => {
  const view = read('../src/views/BackupView.vue');
  const consoleSource = read('../src/composables/useBackupConsole.js');
  const adminMenuItems = navigationGroupsForRole('ADMIN').flatMap((group) => group.items);
  assert.ok(adminMenuItems.some((item) => item.key === 'backup'));
  assert.ok(adminMenuItems.every((item) => item.key !== 'jobs'));
  assert.match(view, /JobTable/);
  assert.match(view, /JobDetail/);
  const table = read('../src/components/jobs/JobTable.vue');
  assert.match(table, /取消中/);
  assert.match(table, /元数据模拟/);
  assert.match(table, /:label="row\.cancelRequested/);
  assert.match(table, /label="存储卷"/);
  assert.match(table, /storageVolumeView/);
  assert.match(table, /volume\(row\)\.current/);
  assert.match(table, /volume\(row\)\.historical/);
  assert.match(table, /row\.targetStorageVolumeId/);
  assert.doesNotMatch(view, /createBackupJob|backupCandidates|type="selection"/);
  assert.match(consoleSource, /\/admin\/backup\/jobs/);
  assert.match(consoleSource, /\/admin\/backup\/schedule\/run-now/);
  assert.match(view, /立即执行扫描/);
  assert.match(consoleSource, /确认立即执行一次手工备份扫描/);
  assert.match(consoleSource, /手工备份扫描已完成/);
  assert.doesNotMatch(consoleSource, /确认立即执行一次自动备份扫描|自动备份扫描已完成/);
  assert.match(consoleSource, /retry-failed/);
  assert.match(consoleSource, /degraded-backup/);
  assert.match(consoleSource, /await loadJobDetails\(job\.id\);\s*if \(!isTerminalJob\(selectedJob\.value\)\) startJobPolling\(\);/);
  assert.doesNotMatch(consoleSource, /\/admin\/backup\/candidates/);
  const detail = read('../src/components/jobs/JobDetail.vue');
  const orderDrawer = read('../src/components/jobs/JobOrderDetailDrawer.vue');
  const orderTable = read('../src/components/orders/OrderTable.vue');
  assert.match(detail, /orderCode/);
  assert.match(orderDrawer, /orderBusinessJson/);
  assert.match(detail, /simulationPath/);
  assert.match(detail, /源文件容量/);
  assert.match(detail, /模拟完成/);
  assert.match(read('../src/components/orders/OrderDetail.vue'), /displayJson/);
  assert.match(orderTable, /文件可能不完整/);
  assert.doesNotMatch(orderTable, /缺失关系/);
  assert.match(orderTable, /<el-tooltip[\s\S]*incompleteBackupTooltip\(row\)/);
  assert.match(orderDrawer, /degradedBackupEligible/);
  assert.match(orderDrawer, /降级备份/);
  assert.match(detail, /canRetryFailedOrders/);
  const orderDetail = read('../src/components/orders/OrderDetail.vue');
  assert.match(orderDetail, /文件可能不完整/);
  assert.match(orderDetail, /<el-tooltip[\s\S]*incompleteBackupTooltip\(selectedOrder\)/);
  assert.match(read('../src/components/jobs/JobTable.vue'), /canRetryFailedOrders/);
  const businessFields = read('../src/components/orders/OrderBusinessFields.vue');
  assert.match(businessFields, /订单信息（备份时）/);
  assert.doesNotMatch(businessFields, /全部业务字段|Object\.entries|JSON\.stringify/);
  assert.match(businessFields, /<el-collapse v-if="presentation\.hasContent && presentation\.rawJson" class="business-raw-json">/);
  assert.match(businessFields, /原始JSON快照/);
  assert.doesNotMatch(businessFields, /原始 JSON（按需查看）/);
  assert.match(businessFields, /\.business-raw-json\s*\{(?=[^}]*margin:\s*20px 0 0;)(?=[^}]*width:\s*100%;)[^}]*\}/);
  assert.match(businessFields, /<pre class="business-raw-json__code"><code>{{ presentation\.rawJson }}<\/code><\/pre>/);
});

test('order file page uses a unified keyword and fixed filter option catalogs', () => {
  const filters = read('../src/components/orders/OrderFilters.vue');
  const table = read('../src/components/orders/OrderTable.vue');
  const consoleSource = read('../src/composables/useBackupConsole.js');
  assert.match(filters, /keyword/);
  assert.match(filters, /orderFilterOptions/);
  assert.match(filters, /orderFilterOptions\.localReplicaStates/);
  assert.match(filters, /needsPhysicalDelivery/);
  assert.match(consoleSource, /params\.set\('keyword'/);
  assert.match(consoleSource, /params\.set\('needsPhysicalDelivery'/);
  assert.match(consoleSource, /\/orders\/filter-options/);
  for (const field of ['snapshotStatus', 'sourceStatus', 'orgName', 'hospitalName', 'patientName', 'doctorName', 'phase']) {
    assert.doesNotMatch(filters, new RegExp(`v-model[^\\n]*${field}`));
  }
  for (const field of ['orgName', 'hospitalName', 'patientName', 'doctorName']) {
    assert.match(table, new RegExp(`row\\.${field}`));
  }
});

test('order file details open in a drawer without coupling bulk selection to detail loading', () => {
  const detail = read('../src/components/orders/OrderDetail.vue');
  const table = read('../src/components/orders/OrderTable.vue');
  const consoleSource = read('../src/composables/useBackupConsole.js');
  const toggleSelectedSource = consoleSource.slice(
    consoleSource.indexOf('function toggleSelected'),
    consoleSource.indexOf('async function loadSelectedOrder'),
  );

  assert.match(detail, /<el-drawer/);
  assert.match(detail, /:model-value="Boolean\(selectedOrder\)"/);
  assert.match(detail, /@closed="closeOrderDetail"/);
  assert.match(detail, /append-to-body/);
  assert.match(table, />详情<\/el-button>/);
  assert.match(table, /@click\.stop="selectOrder\(row\)"/);
  assert.doesNotMatch(table, /type="expand"|OrderBusinessFields/);
  assert.match(consoleSource, /function closeOrderDetail\(\)/);
  assert.doesNotMatch(toggleSelectedSource, /selectOrder\(/);
});

test('order detail supplement area uses Element Plus upload and file cards', () => {
  const detail = read('../src/components/orders/OrderDetail.vue');

  assert.match(detail, /<el-upload[\s\S]*\sdrag(?:\s|>)/);
  assert.match(detail, /<el-empty[^>]*description="尚无补传文件"/);
  assert.match(detail, /<el-card\s+v-for="file in supplements"/);
  assert.match(detail, /<el-descriptions[\s\S]*label="文件大小"[\s\S]*label="存储卷"[\s\S]*label="相对位置"/);
  assert.doesNotMatch(detail, /仅本地/);
});

test('raw JSON snapshot keeps the same parent width before and after expansion', () => {
  const businessFields = read('../src/components/orders/OrderBusinessFields.vue');

  assert.match(businessFields, /\.business-fields\s*\{(?=[^}]*box-sizing:\s*border-box;)(?=[^}]*width:\s*100%;)(?=[^}]*min-width:\s*0;)[^}]*\}/);
  assert.match(businessFields, /\.business-raw-json\s*\{(?=[^}]*width:\s*100%;)(?=[^}]*min-width:\s*0;)(?=[^}]*max-width:\s*100%;)[^}]*\}/);
  assert.match(businessFields, /\.business-raw-json\s+:deep\(\.el-collapse-item__content\)[^{]*\{(?=[^}]*min-width:\s*0;)(?=[^}]*overflow:\s*hidden;)[^}]*\}/);
  assert.match(businessFields, /\.business-raw-json__code\s*\{(?=[^}]*box-sizing:\s*border-box;)(?=[^}]*width:\s*100%;)(?=[^}]*min-width:\s*0;)(?=[^}]*max-width:\s*100%;)(?=[^}]*overflow:\s*auto;)[^}]*\}/);
});

test('order file table keeps detail download and supplement upload in a fixed action column', () => {
  const table = read('../src/components/orders/OrderTable.vue');

  assert.match(table, /label="操作"[^>]*fixed="right"/);
  assert.match(table, /@click\.stop="selectOrder\(row\)">详情<\/el-button>/);
  assert.match(table, /:disabled="!storageState\(row\)\.canDownload"[^>]*@click\.stop="downloadOrder\(row\)">下载<\/el-button>/);
  assert.match(table, /<el-upload[^>]*:on-change="\(file\) => uploadOrderFile\(row, file\)"/);
  assert.match(table, />上传工程文件<\/el-button>/);
  assert.match(table, /function uploadOrderFile\(row, file\)[\s\S]*selectOrder\(row\)[\s\S]*uploadSupplement/);
});

test('order file table exposes a viewport-fixed horizontal scrollbar synchronized with the table', () => {
  const table = read('../src/components/orders/OrderTable.vue');

  assert.match(table, /ref="tableRef"[\s\S]*@scroll="syncFromTable"/);
  assert.match(table, /class="order-table-sticky-scrollbar"[\s\S]*@scroll="syncFromStickyScrollbar"/);
  assert.match(table, /tableRef\.value\?\.setScrollLeft\(stickyScrollbar\.value\.scrollLeft\)/);
  assert.match(table, /\.order-table-sticky-scrollbar\s*\{(?=[^}]*position:\s*fixed;)(?=[^}]*bottom:\s*0;)(?=[^}]*overflow-x:\s*auto;)[^}]*\}/);
});

test('order file table keeps organization and patient columns compact and omits business columns', () => {
  const table = read('../src/components/orders/OrderTable.vue');
  assert.match(table, /label="机构 \/ 医院" width="170"/);
  assert.match(table, /label="患者 \/ 医生" width="120"/);
  assert.doesNotMatch(table, /label="业务类型"/);
  assert.doesNotMatch(table, /row\.businessTypeName|row\.businessType/);
  assert.doesNotMatch(table, /label="业务状态"/);
  assert.doesNotMatch(table, /row\.statusName|row\.sourceStatus|row\.phaseName/);
});

test('order file table formats order creation time to seconds', () => {
  const table = read('../src/components/orders/OrderTable.vue');
  assert.match(table, /formatJobOrderCreatedAt\(row\.orderCreatedAt\)/);
  assert.doesNotMatch(table, /prop="orderCreatedAt"/);
});

test('order file table shows the volume state inside the storage location column', () => {
  const table = read('../src/components/orders/OrderTable.vue');
  assert.match(table, /label="存储卷 \/ 位置"[^\n]*storageMountState\(row\)/);
  assert.doesNotMatch(table, /label="卷状态"/);
});

test('order filters keep physical delivery compact and give all storage selectors the available width', () => {
  const filters = read('../src/components/orders/OrderFilters.vue');
  const styles = read('../src/style-production.css');
  assert.match(filters, /class="physical-filter"/);
  assert.match(filters, /class="storage-filter"/);
  assert.match(styles, /\.order-filter-grid\s*\{[^}]*repeat\(12,minmax\(0,1fr\)\)/);
  assert.match(styles, /\.order-filter \.physical-filter\s*\{\s*grid-column:span 1;\s*\}/);
  assert.match(styles, /\.order-filter \.storage-filter\s*\{\s*grid-column:span 7;\s*\}/);
  assert.match(styles, /\.filter-triple\s*\{(?=[^}]*display:flex;)(?=[^}]*width:100%;)[^}]*\}/);
});
