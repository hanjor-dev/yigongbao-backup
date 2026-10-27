<script setup>
import { useBackupContext } from '../composables/useBackupContext.js';
const { username, password, notice, login } = useBackupContext();
</script>

<template>
  <section class="login card">
    <div class="eyebrow">LOCAL BACKUP CONSOLE</div>
    <h1>本地系统登录</h1>
    <p class="muted">使用本地备份系统账号登录，账号状态与密码独立于线上医工宝。</p>
    <form @submit.prevent="login">
      <label>账号<input v-model.trim="username" autocomplete="username" placeholder="请输入账号" /></label>
      <label>密码<input v-model="password" type="password" autocomplete="current-password" placeholder="请输入密码" /></label>
      <button class="primary" type="submit">登录</button>
    </form>
    <p v-if="notice" class="notice-inline">{{ notice }}</p>
  </section>
</template>
