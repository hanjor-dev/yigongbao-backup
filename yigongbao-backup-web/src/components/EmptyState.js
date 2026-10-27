import { defineComponent } from 'vue';

export const EmptyState = defineComponent({
  name: 'EmptyState',
  props: { message: { type: String, default: '暂无数据' } },
  template: '<div class="empty-state"><span class="empty-icon">∅</span><p>{{ message }}</p></div>'
});
