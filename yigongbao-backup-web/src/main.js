import { createApp } from 'vue';
import ElementPlus from 'element-plus';
import 'element-plus/dist/index.css';
import * as ElementPlusIconsVue from '@element-plus/icons-vue';
import App from './App.vue';
import './style.css';
import './style-production.css';
import { StatusBadge } from './components/StatusBadge.js';
import { MetricCard } from './components/MetricCard.js';
import { EmptyState } from './components/EmptyState.js';

const app = createApp(App);
app.use(ElementPlus);
for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component);
}
app.component('StatusBadge', StatusBadge);
app.component('MetricCard', MetricCard);
app.component('EmptyState', EmptyState);
app.mount('#app');
