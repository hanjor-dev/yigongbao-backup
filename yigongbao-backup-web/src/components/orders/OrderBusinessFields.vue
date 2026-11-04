<script setup>
import { computed } from 'vue';
import { orderBusinessFields } from '../../taskCenter.js';
const props = defineProps({ value: { type: [String, Object], default: '' } });
const fields = computed(() => Object.entries(orderBusinessFields(props.value)));
const display = (value) => {
  if (value === null || value === undefined || value === '') return '-';
  return typeof value === 'object' ? JSON.stringify(value) : String(value);
};
</script>

<template>
  <div class="business-fields">
    <div class="business-fields__title">全部业务字段（{{ fields.length }}）</div>
    <el-descriptions v-if="fields.length" :column="3" border size="small">
      <el-descriptions-item v-for="([key, value]) in fields" :key="key" :label="key">
        <span class="business-fields__value">{{ display(value) }}</span>
      </el-descriptions-item>
    </el-descriptions>
    <el-empty v-else description="暂无业务快照" :image-size="48" />
  </div>
</template>

<style scoped>
.business-fields { padding: 16px 22px; background: #f8fafc; }
.business-fields__title { margin-bottom: 12px; font-weight: 600; color: #334155; }
.business-fields__value { word-break: break-all; white-space: pre-wrap; }
</style>
