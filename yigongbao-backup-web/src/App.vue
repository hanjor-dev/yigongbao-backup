<script setup>
import { computed, onUnmounted, provide, watch } from 'vue';
import { useBackupConsole } from './composables/useBackupConsole.js';
import { BACKUP_CONTEXT } from './composables/useBackupContext.js';
import BackupLayout from './layouts/BackupLayout.vue';
import LoginView from './views/LoginView.vue';
import OverviewView from './views/OverviewView.vue';
import OrdersView from './views/OrdersView.vue';
import StorageView from './views/StorageView.vue';
import AccountsView from './views/AccountsView.vue';
import CleanupView from './views/CleanupView.vue';
import ConfigsView from './views/ConfigsView.vue';
import BackupView from './views/BackupView.vue';
import RestoreView from './views/RestoreView.vue';
import ColdStorageView from './views/ColdStorageView.vue';
import { globalNoticePresenter } from './uiFeedback.js';

const backup = useBackupConsole();
provide(BACKUP_CONTEXT, backup);
const { token, notice, page } = backup;
const stopNoticeWatch = watch(notice, (message) => {
  if (!message) return;
  globalNoticePresenter.show(message, (dismissedMessage) => {
    if (notice.value === dismissedMessage) notice.value = '';
  });
}, { immediate: true });
onUnmounted(() => {
  stopNoticeWatch();
  globalNoticePresenter.dispose();
});

const pageViews = {
  overview: OverviewView,
  orders: OrdersView,
  storage: StorageView,
  'storage-volumes': StorageView,
  accounts: AccountsView,
  cleanup: CleanupView,
  configs: ConfigsView,
  backup: BackupView,
  restore: RestoreView,
  'cold-storage': ColdStorageView
};
const currentView = computed(() => pageViews[page.value] || OrdersView);
</script>

<template>
  <div class="shell app-root">
    <LoginView v-if="!token" />
    <BackupLayout v-else>
      <component :is="currentView" />
    </BackupLayout>
  </div>
</template>
