<script setup>
import { computed, watch } from 'vue';
import { useBackupContext } from '../composables/useBackupContext.js';
const { configs, configCategories, selectedConfigCategory, configKey, configValue, configVersion, saveConfig, editConfig } = useBackupContext();
const visibleConfigs = computed(() => configs.value.filter((item) => !selectedConfigCategory.value || item.categoryCode === selectedConfigCategory.value));
const selectedConfig = computed(() => configs.value.find((item) => item.key === configKey.value) || null);
const configOptions = computed(() => {
  const raw = selectedConfig.value?.optionsJson;
  if (!raw) return [];
  try {
    const parsed = typeof raw === 'string' ? JSON.parse(raw) : raw;
    return Array.isArray(parsed) ? parsed.filter((item) => item && item.value !== undefined) : [];
  } catch (_) { return []; }
});
const configType = computed(() => selectedConfig.value?.valueType || 'STRING');
const selectedCategory = computed(() => configCategories.value.find((item) => item.code === selectedConfigCategory.value));
const dependencyWarning = computed(() => {
  const onlineEnabled = configs.value.find((item) => item.key === 'online.enabled')?.value === 'true';
  const accountEnabled = configs.value.find((item) => item.key === 'account.sync-enabled')?.value === 'true';
  if (accountEnabled && !onlineEnabled) return '账号同步已开启，但线上通信总开关处于关闭状态，账号同步不会执行。';
  return '';
});
function effectiveText(item) {
  if (item?.key?.startsWith('online.')) return '下次线上请求';
  if (item?.key?.includes('schedule') || item?.key?.startsWith('job.')) return '下一调度周期';
  return '保存后生效';
}
watch(selectedConfigCategory, () => {
  const first = visibleConfigs.value[0];
  if (first && !visibleConfigs.value.some((item) => item.key === configKey.value)) editConfig(first);
}, { flush: 'post' });
</script>
<template>
  <div class="page-stack">
    <section class="page-heading">
      <div><div class="eyebrow">SYSTEM CONTROL</div><h2>系统配置</h2><p>备份业务配置统一保存到数据库，修改将保留版本号并在运行时生效。</p></div>
    </section>
    <div class="config-layout">
      <el-card shadow="never" class="category-card">
        <div class="category-title">配置分类</div>
        <el-menu :default-active="selectedConfigCategory" class="category-menu" @select="selectedConfigCategory = $event">
          <el-menu-item v-for="category in configCategories" :key="category.code" :index="category.code">
            <span>{{ category.name }}</span><el-tag size="small" effect="plain">{{ category.configCount }}</el-tag>
          </el-menu-item>
        </el-menu>
      </el-card>
      <div class="config-content">
    <el-alert v-if="dependencyWarning" :title="dependencyWarning" type="warning" show-icon :closable="false" />
    <el-card shadow="never" class="page-card">
      <template #header><div class="config-section-title"><span>{{ selectedCategory?.name || '配置项' }}</span><small>{{ visibleConfigs.length }} 项</small></div></template>
      <el-form inline label-position="top">
        <el-form-item label="配置项">
          <el-select v-model="configKey" filterable style="width:320px" placeholder="选择配置项">
            <el-option v-for="item in visibleConfigs" :key="item.key" :label="item.name || item.key" :value="item.key">
              <span>{{ item.name || item.key }}</span><small class="config-option-key">{{ item.key }}</small>
            </el-option>
          </el-select>
        </el-form-item>
        <el-form-item label="配置值">
          <el-select v-if="configOptions.length" v-model="configValue" style="width:320px">
            <el-option v-for="option in configOptions" :key="String(option.value)" :label="option.label || option.value" :value="String(option.value)" />
          </el-select>
          <el-input v-else v-model="configValue" style="width:320px" :placeholder="configType" />
        </el-form-item>
        <el-form-item label="当前版本"><el-input-number v-model="configVersion" :min="0" controls-position="right" /></el-form-item>
        <el-form-item label="操作"><el-button type="primary" @click="saveConfig">保存配置</el-button></el-form-item>
      </el-form>
      <div v-if="selectedConfig?.description" class="config-help">
        <span class="help-icon">?</span>{{ selectedConfig.description }}
        <el-tag size="small" effect="plain">{{ effectiveText(selectedConfig) }}</el-tag>
      </div>
    </el-card>
    <el-card shadow="never" class="table-card">
      <el-table :data="visibleConfigs" class="data-table">
        <el-table-column prop="name" label="配置名称" min-width="220" />
        <el-table-column prop="key" label="配置键" min-width="285" />
        <el-table-column prop="value" label="当前值" min-width="180" show-overflow-tooltip />
        <el-table-column prop="valueType" label="类型" width="110" />
        <el-table-column label="生效时间" width="130"><template #default="{ row }"><el-tag size="small" effect="plain">{{ effectiveText(row) }}</el-tag></template></el-table-column>
        <el-table-column label="说明" min-width="260">
          <template #default="{ row }">
            <el-tooltip v-if="row.description" :content="row.description" placement="top-start"><span class="help-icon">?</span></el-tooltip>
            <span v-else class="muted">暂无说明</span>
          </template>
        </el-table-column>
        <el-table-column prop="version" label="版本" width="80" />
        <el-table-column label="操作" width="90"><template #default="{ row }"><el-button link type="primary" @click="editConfig(row)">编辑</el-button></template></el-table-column>
        <template #empty><el-empty description="当前分类暂无配置，请先执行初始化脚本" /></template>
      </el-table>
    </el-card>
      </div>
    </div>
  </div>
</template>

<style scoped>
.config-layout { display:grid; grid-template-columns:220px minmax(0,1fr); gap:16px; align-items:start; }
.category-card { position:sticky; top:16px; }
.category-title { padding:4px 12px 12px; color:var(--el-text-color-primary); font-weight:650; }
.category-menu { border-right:0; }
.category-menu :deep(.el-menu-item) { display:flex; justify-content:space-between; gap:12px; border-radius:8px; margin-bottom:4px; }
.config-content { min-width:0; display:flex; flex-direction:column; gap:16px; }
.config-section-title { display:flex; align-items:center; justify-content:space-between; font-weight:650; }
.config-section-title small { color:var(--el-text-color-secondary); font-weight:400; }
.config-help { display:flex; align-items:center; gap:8px; margin-top:4px; color:var(--el-text-color-secondary); font-size:13px; }
.help-icon { display:inline-flex; width:18px; height:18px; align-items:center; justify-content:center; border:1px solid var(--el-color-primary); border-radius:50%; color:var(--el-color-primary); font-size:12px; font-weight:700; cursor:help; }
.config-option-key { margin-left:10px; color:var(--el-text-color-secondary); }
.muted { color:var(--el-text-color-secondary); }
</style>
