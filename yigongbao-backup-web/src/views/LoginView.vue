<script setup>
import { ref } from 'vue';
import { Lock, User } from '@element-plus/icons-vue';
import { useBackupContext } from '../composables/useBackupContext.js';
const { username, password, notice, login } = useBackupContext();
const loading = ref(false);
async function submit() { loading.value = true; try { await login(); } finally { loading.value = false; } }
</script>

<template>
  <div class="login-screen">
    <div class="login-orbit orbit-one" /><div class="login-orbit orbit-two" />
    <section class="login-panel">
      <div class="login-brand"><div class="brand-mark"><DataAnalysis /></div><div><strong>医工宝</strong><span>本地备份中心</span></div></div>
      <div class="login-copy"><h1>安全访问<br /><em>订单资产</em></h1><p>集中管理订单热备、冷备与恢复任务，所有操作均保留完整审计轨迹。</p></div>
      <el-form class="login-form" label-position="top" @submit.prevent="submit">
        <el-form-item label="账号"><el-input v-model.trim="username" size="large" autocomplete="username" placeholder="请输入本地账号" :prefix-icon="User" /></el-form-item>
        <el-form-item label="密码"><el-input v-model="password" size="large" type="password" show-password autocomplete="current-password" placeholder="请输入登录密码" :prefix-icon="Lock" /></el-form-item>
        <el-alert v-if="notice" :title="notice" type="error" :closable="false" show-icon />
        <el-button native-type="submit" type="primary" size="large" class="login-submit" :loading="loading">进入备份中心 <el-icon><ArrowRight /></el-icon></el-button>
      </el-form>
      <div class="login-foot"><span class="online-dot" /> 本地服务已就绪 <span>HTTP · 内网安全域</span></div>
    </section>
    <aside class="login-aside"><div class="aside-grid" /><div class="login-aside-content"><h2>让数字资产<br />始终可追溯</h2><p>从 COS 到 NAS，再到离线冷备，每一份订单文件都拥有清晰、可靠的生命周期。</p><div class="login-stat-row"><div><strong>3</strong><span>存储副本</span></div><div><strong>24/7</strong><span>任务守护</span></div><div><strong>100%</strong><span>操作留痕</span></div></div></div></aside>
  </div>
</template>
