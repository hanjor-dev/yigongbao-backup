<script setup>
import { computed, provide } from 'vue';
import { useBackupConsole } from './composables/useBackupConsole.js';
import { BACKUP_CONTEXT } from './composables/useBackupContext.js';
import BackupLayout from './layouts/BackupLayout.vue';
import LoginView from './views/LoginView.vue';
import OverviewView from './views/OverviewView.vue';
import AuditView from './views/AuditView.vue';
import OrdersView from './views/OrdersView.vue';
import StorageView from './views/StorageView.vue';
import AccountsView from './views/AccountsView.vue';
import CleanupView from './views/CleanupView.vue';
import ColdView from './views/ColdView.vue';
import ConfigsView from './views/ConfigsView.vue';
import BackupView from './views/BackupView.vue';

const backup = useBackupConsole();
provide(BACKUP_CONTEXT, backup);
const { token, notice, page } = backup;

const pageViews = {
  overview: OverviewView,
  audit: AuditView,
  orders: OrdersView,
  storage: StorageView,
  accounts: AccountsView,
  cleanup: CleanupView,
  cold: ColdView,
  configs: ConfigsView,
  backup: BackupView
};
const currentView = computed(() => pageViews[page.value] || OrdersView);
</script>

<template>
  <div class="shell app-root">
    <LoginView v-if="!token" />
    <BackupLayout v-else>
      <component :is="currentView" />
    </BackupLayout>
    <el-alert v-if="notice" class="global-notice" type="warning" :closable="false" show-icon><template #title>{{ notice }}</template></el-alert>
  </div>
</template>
