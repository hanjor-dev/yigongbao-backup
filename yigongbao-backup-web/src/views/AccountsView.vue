<script setup>
import { useBackupContext } from '../composables/useBackupContext.js';
const { accounts, toggleAccount, accountPassword, resetAccountPassword,
  accountPage, accountSize, accountTotal, changeAccountPage, changeAccountSize,
  accountSyncStatus, accountSyncRunning, syncAccountsNow, loadAccounts } = useBackupContext();

function time(value) {
  return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '尚未执行';
}
</script>
<template>
  <div class="page-stack">
    <section class="page-heading">
      <div><h2>本地账号</h2><p>账号资料从线上增量同步，本地密码与启停状态独立管理。</p></div>
      <div class="heading-actions"><el-button @click="loadAccounts()"><el-icon><Refresh /></el-icon>刷新</el-button><el-button type="primary" :loading="accountSyncRunning" :disabled="!accountSyncStatus?.syncEnabled || !accountSyncStatus?.onlineEnabled" @click="syncAccountsNow">立即同步</el-button></div>
    </section>
    <el-alert v-if="accountSyncStatus && (!accountSyncStatus.syncEnabled || !accountSyncStatus.onlineEnabled)" type="warning" show-icon :closable="false" title="账号同步当前不可执行" description="请同时开启账号同步和线上通信，并确认线上连接认证配置完整。" />
    <el-card shadow="never" class="table-card">
      <template #header><div class="sync-summary"><strong>同步状态</strong><status-badge :value="accountSyncStatus?.status || 'IDLE'" /><span>最近成功：{{ time(accountSyncStatus?.lastSuccessAt) }}</span><span>最近处理：{{ accountSyncStatus?.lastItemCount || 0 }} 条</span></div></template>
      <el-alert v-if="accountSyncStatus?.lastErrorMessage" type="error" show-icon :closable="false" :title="accountSyncStatus.lastErrorMessage" />
      <el-table :data="accounts" class="data-table">
        <el-table-column prop="username" label="账号" min-width="160" /><el-table-column prop="realName" label="姓名" min-width="130" />
        <el-table-column label="角色" width="120"><template #default="{ row }"><el-tag :type="row.roleCode === 'ADMIN' ? 'warning' : 'info'" effect="light">{{ row.roleCode }}</el-tag></template></el-table-column>
        <el-table-column label="本地状态" width="130"><template #default="{ row }"><status-badge :value="row.enabledStatus" :label="row.enabledStatus === 'ENABLED' ? '已启用' : '已禁用'" /></template></el-table-column>
        <el-table-column label="最近同步" min-width="180"><template #default="{ row }">{{ time(row.lastSyncedAt) }}</template></el-table-column>
        <el-table-column label="操作" min-width="420"><template #default="{ row }"><div class="row-actions"><el-button size="small" :type="row.enabledStatus === 'ENABLED' ? 'danger' : 'success'" plain @click="toggleAccount(row)">{{ row.enabledStatus === 'ENABLED' ? '禁用账号' : '启用账号' }}</el-button><el-input v-model="accountPassword[row.id]" size="small" type="password" show-password placeholder="新密码（至少 8 位）" style="max-width:190px" /><el-button size="small" type="primary" @click="resetAccountPassword(row)">重置密码</el-button></div></template></el-table-column>
        <template #empty><el-empty description="暂无同步账号" /></template>
      </el-table>
      <div class="table-pagination"><el-pagination background layout="total, sizes, prev, pager, next, jumper" :current-page="accountPage" :page-size="accountSize" :total="accountTotal" :page-sizes="[10, 20, 50, 100]" @current-change="changeAccountPage" @size-change="changeAccountSize" /></div>
    </el-card>
  </div>
</template>
<style scoped>
.sync-summary { display:flex; align-items:center; gap:16px; flex-wrap:wrap; color:var(--el-text-color-regular); }
.sync-summary strong { color:var(--el-text-color-primary); }
.heading-actions { display:flex; gap:10px; }
.table-pagination { display:flex; justify-content:flex-end; padding:18px 4px 2px; }
</style>
