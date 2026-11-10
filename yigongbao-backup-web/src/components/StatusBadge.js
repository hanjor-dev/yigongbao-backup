import { computed, defineComponent, h } from 'vue';

export function statusTone(value) {
  const normalized = String(value ?? '').toUpperCase();
  if (normalized === 'HOT') return 'warm';
  if (normalized === 'COLD') return 'cold';
  if (['IMPORT', 'LOCAL', 'HOT_NAS', 'COS'].includes(normalized)) return 'info';
  if (['SUCCESS', 'SUCCEEDED', 'SIMULATED', 'COMPLETED', 'VERIFIED', 'PRESENT', 'EXISTS', 'AVAILABLE', 'ENABLED', 'ACTIVE', 'ONLINE', 'CLEANED', 'CLEARED', 'REGISTERED', 'COMMITTED', 'UPLOADED', 'RELEASED', 'ISSUED', 'CONSUMED', 'DELETED'].includes(normalized)) return 'success';
  if (['CREATED', 'RUNNING', 'WRITING', 'PENDING', 'PENDING_RECEIPT', 'PREPARED', 'PROCESSING', 'RETRYING', 'RETRY_PENDING', 'RETRYING_FAILED_ORDERS', 'RESTORING', 'UPLOADING', 'SEALING', 'ARCHIVE_GUARD', 'GOING_OFFLINE', 'MOUNTING', 'REQUESTED', 'VERIFYING', 'COMMITTING', 'CLAIMED'].includes(normalized)) return 'progress';
  if (['PARTIAL', 'PAUSED', 'READY', 'SWITCHED', 'MANIFEST_FROZEN', 'UPLOAD_UNKNOWN', 'UNVERIFIED'].includes(normalized)) return 'warning';
  if (['FAILED', 'PARTIAL_FAILED', 'PREPARE_FAILED', 'VERIFY_FAILED', 'COMMIT_FAILED', 'SIMULATION_FAILED', 'CANCELLED', 'CANCELED', 'ABANDONED', 'ABSENT', 'DISABLED', 'UNAVAILABLE', 'ERROR', 'DAMAGED', 'REPAIR_REQUIRED', 'EXPIRED', 'REVOKED'].includes(normalized)) return 'danger';
  if (['STANDBY', 'READ_ONLY', 'RETIRED', 'OFFLINE'].includes(normalized)) return 'neutral';
  return 'neutral';
}

const STATUS_LABELS = {
  CREATED: '待执行', RUNNING: '执行中', RETRYING_FAILED_ORDERS: '失败订单重试中', PAUSED: '已暂停',
  SUCCESS: '成功', SUCCEEDED: '成功', COMPLETED: '已完成', FAILED: '失败', PARTIAL_FAILED: '部分失败',
  CANCELLED: '已取消', CANCELED: '已取消', PENDING: '待处理', PROCESSING: '处理中',
  RETRYING: '重试中', RETRY_PENDING: '等待重试', SIMULATED: '模拟完成', SIMULATION_FAILED: '模拟失败',
  AVAILABLE: '可用', UNAVAILABLE: '不可用', PRESENT: '存在', EXISTS: '存在', ABSENT: '缺失', SKIPPED: '已跳过', UNKNOWN: '未知', ERROR: '异常',
  RESTORING: '恢复中', UPLOADING: '上传中', PARTIAL: '部分异常', CLEARED: '已清理', ENABLED: '已启用', DISABLED: '已停用', RETIRED: '已退役',
  VERIFIED: '已校验', WRITING: '写入中', CLEANED: '已清理', READY: '待清理', DAMAGED: '已损坏', UNVERIFIED: '未校验',

  HOT: '热备', COLD: '冷备', IMPORT: '导入区',
  ONLINE: '在线', OFFLINE: '离线',
  ACTIVE: '活动', STANDBY: '备用', READ_ONLY: '只读',
  IDLE: '空闲', SEALING: '封存中', ARCHIVE_GUARD: '封存保护中', GOING_OFFLINE: '准备离线',
  MOUNTING: '挂载确认中', REPAIR_REQUIRED: '需要修复',

  REQUESTED: '已申请', SWITCHED: '已切换活动卷', MANIFEST_FROZEN: '清单已冻结',
  VERIFYING: '校验中', COMMITTING: '提交中', PREPARE_FAILED: '准备失败',
  VERIFY_FAILED: '校验失败', COMMIT_FAILED: '提交失败', ABANDONED: '已放弃',

  ORDER_PACKAGE: '订单备份包', SUPPLEMENT_FILE: '补传文件', ARTIFACT_COPY: '文件副本',
  ORDER_BACKUP: '订单备份', MANUAL_BACKUP: '手动备份', RESTORE: '线上恢复', VOLUME_ARCHIVE: '整盘封存',
  DOWNLOAD: '文件下载', BACKUP_DOWNLOAD: '备份下载', RESTORE_UPLOAD: '恢复包上传',
  RESTORE_OBJECT_UPLOAD: '文件上传', SUPPLEMENT_UPLOAD: '补传上传',

  FORCE_OVERWRITE: '强制覆盖', CONFIRM_OVERWRITE: '确认覆盖', REJECT: '拒绝冲突',
  METADATA_SIMULATION: '元数据模拟', REAL: '真实执行',
  LOCAL: '本地磁盘', HOT_NAS: '热备网络存储', COS: '云对象存储',

  REGISTERED: '已登记', PREPARED: '已准备', COMMITTED: '已提交', UPLOADED: '已上传', RELEASED: '已释放',
  PENDING_RECEIPT: '等待回执', UPLOAD_UNKNOWN: '上传结果未知', SKIPPED_DELETED: '已跳过（源已删除）',
  ISSUED: '已签发', CLAIMED: '已领取', CONSUMED: '已使用', EXPIRED: '已过期', REVOKED: '已撤销', DELETED: '已删除',
  INFO: '信息', WARN: '警告', WARNING: '警告',

  REGISTER: '纳管存储卷', CONFIRM_MOUNTED: '确认挂载', CHANGE_STATE: '变更卷状态',
  SET_ACTIVE: '设为活动卷', SWITCH_ACTIVE: '切换活动卷', FAILOVER: '故障切换',
  STORAGE_VOLUME_ARCHIVE_STARTED: '启动整盘封存', STORAGE_VOLUME_ARCHIVE_PREPARE_FAILED: '封存准备失败',
  STORAGE_VOLUME_ARCHIVE_CANCELLED: '取消整盘封存', STORAGE_VOLUME_ARCHIVE_ABANDONED: '放弃封存尝试',
  STORAGE_VOLUME_ARCHIVE_SUCCEEDED: '整盘封存成功', STORAGE_VOLUME_ARCHIVE_VERIFY_FAILED: '封存校验失败',
  STORAGE_VOLUME_ARCHIVE_COMMIT_FAILED: '封存提交失败',
  LOGIN_SUCCESS: '登录成功', LOGIN_FAILURE: '登录失败', PASSWORD_CHANGE: '修改密码',
  ACCOUNT_SYNC_MANUAL: '手动同步账号', ACCOUNT_STATUS_CHANGE: '变更账号状态', ADMIN_PASSWORD_RESET: '管理员重置密码',
  STORAGE_VOLUME_REGISTER: '纳管存储卷', STORAGE_VOLUME_CONFIRM_MOUNTED: '确认存储卷挂载',
  STORAGE_VOLUME_CHANGE_STATE: '变更存储卷状态', STORAGE_VOLUME_SET_ACTIVE: '设为活动卷',
  STORAGE_VOLUME_SWITCH_ACTIVE: '切换活动卷', STORAGE_VOLUME_FAILOVER: '存储卷故障切换',
  STORAGE_VOLUME_UNAVAILABLE: '存储卷不可用', STORAGE_VOLUME_SWITCH_RECOMMENDED: '建议切换活动卷',
  STORAGE_VOLUME_IDENTITY_VERIFY_FAILED: '存储卷身份校验失败', STORAGE_VOLUME_NO_WRITABLE_CANDIDATE: '无可写存储卷',
  STORAGE_VOLUME_PREPARE_OFFLINE: '存储卷准备离线', ARTIFACT_CHECKSUM_FAILED: '文件校验和失败',
  OK: '成功', AUTH_FAILED: '认证失败',
  INCOMPLETE: '文件可能不完整', COMPLETE: '文件完整'
};

const WORKFLOW_LABELS = {
  SUPPLEMENT: '补传文件', HOT_CLEANUP: '热备清理', COLD_WRITE: '冷备写出', COLD_IMPORT: '冷备导入',
  COS_CLEANUP: 'COS 清理', COS_DRY_RUN: 'COS 清理预检', BACKUP_SIMULATION: '备份模拟', BACKUP_ROLLBACK: '备份回滚',
  STARTED: '已开始', FINAL_VERIFY: '最终校验', ARCHIVE_START: '封存开始', RETRY_FAILED_ORDERS: '重试失败订单',
  CLEANUP_EVALUATE: '清理评估', CLEANUP_EVALUATED: '清理评估完成', CLEANUP_COMPLETED: '清理完成',
  CLEANUP_PERMIT: '获取清理许可', POLL_PERMIT: '轮询清理许可',
  SUPPLEMENT_VALIDATE: '补传校验', SUPPLEMENT_COMMITTED: '补传提交完成',
  COLD_WRITE_START: '冷备写出开始', COLD_WRITE_COPY: '冷备复制', COLD_WRITE_COMPLETED: '冷备写出完成',
  COLD_WRITE_ALREADY_COMPLETED: '冷备写出已完成', COLD_IMPORT_VALIDATE: '冷备导入校验', COLD_IMPORT_COMPLETED: '冷备导入完成',
  HOT_CLEANUP_START: '热备清理开始', HOT_CLEANUP_COMPLETED: '热备清理完成',
  BACKUP_ORDER_COMPLETED: '订单备份完成', RESTORE_ORDER_COMPLETED: '订单恢复完成'
};

export function statusLabel(value) {
  const normalized = String(value ?? '').toUpperCase();
  return STATUS_LABELS[normalized] || (value == null || value === '' ? '-' : String(value));
}

export function workflowLabel(value) {
  const normalized = String(value ?? '').toUpperCase();
  if (WORKFLOW_LABELS[normalized]) return WORKFLOW_LABELS[normalized];
  const dynamicPrefixes = [
    ['RETRY_BACKUP_ORDER_', '重试备份订单 '], ['BACKUP_ORDER_', '备份订单 '], ['BACKUP_COMMIT_', '提交备份 '],
    ['RESTORE_ORDER_', '恢复订单 ']
  ];
  const match = dynamicPrefixes.find(([prefix]) => normalized.startsWith(prefix));
  return match ? `${match[1]}${String(value).slice(match[0].length)}` : statusLabel(value);
}

export const StatusBadge = defineComponent({
  name: 'StatusBadge',
  props: { value: { type: [String, Number], default: '' }, label: { type: String, default: '' } },
  setup(props) {
    const tone = computed(() => statusTone(props.value));
    return () => h('span', { class: ['status-badge', `status-${tone.value}`] }, props.label || statusLabel(props.value));
  }
});
