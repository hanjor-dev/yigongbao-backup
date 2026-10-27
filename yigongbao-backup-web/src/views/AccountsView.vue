<script setup>
import { useBackupContext } from '../composables/useBackupContext.js';
const { accounts, toggleAccount, accountPassword, resetAccountPassword } = useBackupContext();
</script>

<template>
  <section class="card page-card"><div class="toolbar"><div><div class="eyebrow">IDENTITY</div><h2>本地账号</h2></div></div><p class="muted">线上账号只负责初始同步；本地启停和密码修改不会回写线上。</p><div class="table-wrap"><table><thead><tr><th>账号</th><th>姓名</th><th>角色</th><th>本地状态</th><th>操作</th></tr></thead><tbody><tr v-for="a in accounts" :key="a.id"><td>{{a.username}}</td><td>{{a.realName || '-'}}</td><td>{{a.roleCode}}</td><td><status-badge :value="a.enabledStatus" /></td><td class="row-actions"><button @click="toggleAccount(a)">{{a.enabledStatus === 'ENABLED' ? '禁用' : '启用'}}</button><input v-model="accountPassword[a.id]" type="password" placeholder="新密码（至少8位）" /><button @click="resetAccountPassword(a)">重置本地密码</button></td></tr></tbody></table><empty-state v-if="!accounts.length" message="暂无同步账号" /></div></section>
</template>
