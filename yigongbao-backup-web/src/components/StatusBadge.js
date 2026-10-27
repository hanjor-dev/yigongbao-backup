import { computed, defineComponent } from 'vue';

export function statusTone(value) {
  const normalized = String(value || '').toUpperCase();
  if (['SUCCEEDED', 'COMPLETED', 'VERIFIED', 'PRESENT', 'ENABLED', 'CLEANED'].includes(normalized)) return 'success';
  if (['RUNNING', 'WRITING', 'PENDING', 'PROCESSING'].includes(normalized)) return 'progress';
  if (['FAILED', 'CANCELLED', 'CANCELED', 'ABSENT', 'DISABLED'].includes(normalized)) return 'danger';
  return 'neutral';
}

export const StatusBadge = defineComponent({
  name: 'StatusBadge',
  props: { value: { type: [String, Number], default: '' }, label: { type: String, default: '' } },
  setup(props) { return { tone: computed(() => statusTone(props.value)) }; },
  template: '<span class="status-badge" :class="`status-${tone}`">{{ label || value || "-" }}</span>'
});
