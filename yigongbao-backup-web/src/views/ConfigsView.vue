<script setup>
import { useBackupContext } from '../composables/useBackupContext.js';
const { configs, configKey, configValue, configVersion, saveConfig, editConfig } = useBackupContext();
</script>

<template>
  <section class="card page-card"><div class="toolbar"><div><div class="eyebrow">SYSTEM CONTROL</div><h2>系统配置</h2></div></div><div class="filters"><input v-model.trim="configKey" placeholder="配置键" /><input v-model="configValue" placeholder="配置值" /><input v-model.number="configVersion" type="number" min="0" /><button class="primary" @click="saveConfig">保存配置</button></div><div class="table-wrap"><table><thead><tr><th>配置键</th><th>配置值</th><th>版本</th><th>操作</th></tr></thead><tbody><tr v-for="entry in configs" :key="entry.key"><td>{{entry.key}}</td><td class="mono">{{entry.value}}</td><td>{{entry.version}}</td><td><button @click="editConfig(entry)">编辑</button></td></tr></tbody></table><empty-state v-if="!configs.length" message="暂无系统配置" /></div></section>
</template>
