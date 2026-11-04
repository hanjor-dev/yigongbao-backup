import { computed, defineComponent, h } from 'vue';

export function statusTone(value) {
  const normalized = String(value || '').toUpperCase();
  if (['SUCCEEDED', 'COMPLETED', 'VERIFIED', 'PRESENT', 'ENABLED', 'CLEANED'].includes(normalized)) return 'success';
  if (['RUNNING', 'WRITING', 'PENDING', 'PROCESSING', 'RETRYING', 'RETRY_PENDING', 'RETRYING_FAILED_ORDERS'].includes(normalized)) return 'progress';
  if (['FAILED', 'PARTIAL_FAILED', 'CANCELLED', 'CANCELED', 'ABSENT', 'DISABLED'].includes(normalized)) return 'danger';
  return 'neutral';
}

export const StatusBadge = defineComponent({
  name: 'StatusBadge',
  props: { value: { type: [String, Number], default: '' }, label: { type: String, default: '' } },
  setup(props) {
    const tone = computed(() => statusTone(props.value));
    return () => h('span', { class: ['status-badge', `status-${tone.value}`] }, props.label || props.value || '-');
  }
});
