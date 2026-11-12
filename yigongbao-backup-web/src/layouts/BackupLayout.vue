<script setup>
import { computed, ref } from 'vue';
import { useBackupContext } from '../composables/useBackupContext.js';
import { navigationGroupsForRole } from '../stores/routeStore.js';

const { role, logout, page, navigate, selfPassword, changeSelfPassword } = useBackupContext();
const passwordDialog = ref(false);
const passwordLoading = ref(false);
const groups = computed(() => navigationGroupsForRole(role.value));
async function submitPassword() { passwordLoading.value = true; try { await changeSelfPassword(); passwordDialog.value = false; } finally { passwordLoading.value = false; } }
</script>

<template>
  <el-container class="console-shell">
    <el-aside class="console-aside" width="248px">
      <div class="aside-brand"><div class="brand-mark"><DataAnalysis /></div><div><strong>医工宝</strong><span>本地备份中心</span></div></div>
      <div class="aside-caption">BACKUP OPERATIONS</div>
      <el-menu :default-active="page" class="console-menu" :collapse-transition="false" @select="navigate">
        <template v-for="group in groups" :key="group.title">
          <div class="menu-group-title">{{ group.title }}</div>
          <el-menu-item v-for="item in group.items" :key="item.key" :index="item.key"><el-icon><component :is="item.icon" /></el-icon><span>{{ item.label }}</span></el-menu-item>
        </template>
      </el-menu>
    </el-aside>
    <el-container class="console-main">
      <el-header class="console-header"><div><h1>{{ groups.flatMap(g => g.items).find(i => i.key === page)?.label || '订单文件' }}</h1></div><div class="header-actions"><el-tag effect="plain" type="success"><span class="user-dot" /> {{ role === 'ADMIN' ? '系统管理员' : '普通用户' }}</el-tag><el-dropdown trigger="click"><el-button circle plain><el-icon><MoreFilled /></el-icon></el-button><template #dropdown><el-dropdown-menu><el-dropdown-item @click="passwordDialog = true"><el-icon><Lock /></el-icon>修改本地密码</el-dropdown-item><el-dropdown-item divided @click="logout"><el-icon><SwitchButton /></el-icon>退出登录</el-dropdown-item></el-dropdown-menu></template></el-dropdown></div></el-header>
      <el-main class="console-content"><slot /></el-main>
    </el-container>
  </el-container>
  <el-dialog v-model="passwordDialog" title="修改本地密码" width="420px" destroy-on-close><el-form label-position="top"><el-form-item label="新密码"><el-input v-model="selfPassword" type="password" show-password placeholder="至少 8 位字符" /></el-form-item></el-form><template #footer><el-button @click="passwordDialog = false">取消</el-button><el-button type="primary" :loading="passwordLoading" @click="submitPassword">保存密码</el-button></template></el-dialog>
</template>
