import { computed, defineComponent, h } from 'vue';

export function statusTone(value) {
  const normalized = String(value || '').toUpperCase();
  if (['SUCCEEDED', 'COMPLETED', 'VERIFIED', 'PRESENT', 'ENABLED', 'CLEANED'].includes(normalized)) return 'success';
  if (['RUNNING', 'WRITING', 'PENDING', 'PROCESSING', 'RETRYING', 'RETRY_PENDING', 'RETRYING_FAILED_ORDERS'].includes(normalized)) return 'progress';
  if (['FAILED', 'PARTIAL_FAILED', 'CANCELLED', 'CANCELED', 'ABSENT', 'DISABLED'].includes(normalized)) return 'danger';
  return 'neutral';
}

const STATUS_LABELS = {
  CREATED: '待执行', RUNNING: '执行中', RETRYING_FAILED_ORDERS: '失败订单重试中', PAUSED: '已暂停',
  SUCCEEDED: '成功', COMPLETED: '已完成', FAILED: '失败', PARTIAL_FAILED: '部分失败',
  CANCELLED: '已取消', CANCELED: '已取消', PENDING: '待处理', PROCESSING: '处理中',
  RETRYING: '重试中', RETRY_PENDING: '等待重试', SIMULATED: '模拟完成', SIMULATION_FAILED: '模拟失败',
  AVAILABLE: '可用', PRESENT: '存在', ABSENT: '缺失', SKIPPED: '已跳过', UNKNOWN: '未知', ERROR: '异常',
  RESTORING: '恢复中', PARTIAL: '部分存在', CLEARED: '已清理', ENABLED: '已启用', DISABLED: '已禁用',
  VERIFIED: '已核验', WRITING: '写入中', CLEANED: '已清理', INCOMPLETE: '文件可能不完整', COMPLETE: '文件完整'
};

export function statusLabel(value) {
  const normalized = String(value || '').toUpperCase();
  return STATUS_LABELS[normalized] || String(value || '-');
}

export const StatusBadge = defineComponent({
  name: 'StatusBadge',
  props: { value: { type: [String, Number], default: '' }, label: { type: String, default: '' } },
  setup(props) {
    const tone = computed(() => statusTone(props.value));
    return () => h('span', { class: ['status-badge', `status-${tone.value}`] }, props.label || statusLabel(props.value));
  }
});
