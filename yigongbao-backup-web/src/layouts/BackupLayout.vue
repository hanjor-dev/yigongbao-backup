<script setup>
import { useBackupContext } from '../composables/useBackupContext.js';
const { role, logout, page, navigate, isAdmin, selfPassword, changeSelfPassword } = useBackupContext();
</script>

<template>
  <header>
    <div class="brand">医工宝 <span>本地备份管理</span></div>
    <div class="status">{{ role }} <button class="logout" @click="logout">退出</button></div>
  </header>
  <main>
    <nav>
      <template v-if="isAdmin">
        <button :class="{active: page==='overview'}" @click="navigate('overview')">管理概览</button>
        <button :class="{active: page==='audit'}" @click="navigate('audit')">审计日志</button>
      </template>
      <button :class="{active: page==='orders'}" @click="navigate('orders')">订单文件</button>
      <span class="password-box">
        <input v-model="selfPassword" type="password" placeholder="新本地密码"/>
        <button @click="changeSelfPassword">修改密码</button>
      </span>
      <template v-if="isAdmin">
        <button :class="{active: page==='jobs'}" @click="navigate('jobs')">任务中心</button>
        <button :class="{active: page==='accounts'}" @click="navigate('accounts')">本地账号</button>
        <button :class="{active: page==='storage'}" @click="navigate('storage')">容量监控</button>
        <button :class="{active: page==='cold'}" @click="navigate('cold')">冷备恢复</button>
        <button :class="{active: page==='cleanup'}" @click="navigate('cleanup')">COS清理</button>
        <button :class="{active: page==='configs'}" @click="navigate('configs')">系统配置</button>
      </template>
    </nav>
    <slot />
  </main>
</template>

