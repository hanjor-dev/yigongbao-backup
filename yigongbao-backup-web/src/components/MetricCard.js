import { defineComponent } from 'vue';

export const MetricCard = defineComponent({
  name: 'MetricCard',
  props: { label: { type: String, required: true }, value: { type: [String, Number], default: 0 }, detail: { type: String, default: '' } },
  template: '<article class="metric-card"><span class="metric-label">{{ label }}</span><strong class="metric-value">{{ value }}</strong><small v-if="detail">{{ detail }}</small></article>'
});
