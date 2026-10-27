import { createApp } from 'vue';
import App from './App.vue';
import './style.css';
import './style-production.css';
import { StatusBadge } from './components/StatusBadge.js';
import { MetricCard } from './components/MetricCard.js';
import { EmptyState } from './components/EmptyState.js';

const app = createApp(App);
app.component('StatusBadge', StatusBadge);
app.component('MetricCard', MetricCard);
app.component('EmptyState', EmptyState);
app.mount('#app');

