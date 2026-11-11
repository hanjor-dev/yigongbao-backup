<script setup>
import { computed } from 'vue';
import { buildOrderBusinessPresentation } from '../../orderBusinessPresentation.js';

const props = defineProps({
  value: { type: [String, Object], default: '' },
  title: { type: String, default: '订单信息（备份时）' },
});
const presentation = computed(() => buildOrderBusinessPresentation(props.value));
</script>

<template>
  <div class="business-fields">
    <header v-if="title" class="business-fields__header">
      <strong>{{ title }}</strong>
      <span>以下内容为执行备份时的线上订单信息，不随线上订单后续修改而变化。</span>
    </header>

    <div v-if="presentation.hasContent" class="business-fields__content">
      <section v-for="section in presentation.sections" :key="section.key" class="business-section">
        <h4>{{ section.title }}</h4>
        <dl class="business-grid">
          <div v-for="field in section.items" :key="field.label" class="business-field" :class="{ 'business-field--full': field.full }">
            <dt>{{ field.label }}</dt>
            <dd>
              <el-tag v-if="field.kind === 'tag'" size="small" effect="plain">{{ field.value }}</el-tag>
              <span v-else>{{ field.value }}</span>
            </dd>
          </div>
        </dl>
      </section>

      <section v-if="presentation.rebuildProjects.length" class="business-section">
        <h4>重建项目</h4>
        <div class="business-card-list">
          <article v-for="project in presentation.rebuildProjects" :key="project.title" class="business-card">
            <strong>{{ project.title }}</strong>
            <dl class="business-grid business-grid--nested">
              <div v-for="field in project.items" :key="field.label" class="business-field" :class="{ 'business-field--full': field.full }">
                <dt>{{ field.label }}</dt><dd>{{ field.value }}</dd>
              </div>
            </dl>
          </article>
        </div>
      </section>

      <section v-if="presentation.audits.length" class="business-section">
        <h4>审核记录</h4>
        <div class="business-card-list">
          <article v-for="audit in presentation.audits" :key="audit.title" class="business-card">
            <strong>{{ audit.title }}</strong>
            <dl class="business-grid business-grid--nested">
              <div v-for="field in audit.items" :key="field.label" class="business-field" :class="{ 'business-field--full': field.full }">
                <dt>{{ field.label }}</dt>
                <dd><el-tag v-if="field.kind === 'tag'" size="small" effect="plain">{{ field.value }}</el-tag><span v-else>{{ field.value }}</span></dd>
              </div>
            </dl>
          </article>
        </div>
      </section>
    </div>
    <el-empty v-else description="暂无可展示的订单信息" :image-size="48" />

    <el-collapse v-if="presentation.rawJson" class="business-raw-json">
      <el-collapse-item name="raw-json">
        <template #title>
          <span class="business-raw-json__title">原始JSON快照</span>
        </template>
        <pre class="business-raw-json__code"><code>{{ presentation.rawJson }}</code></pre>
      </el-collapse-item>
    </el-collapse>
  </div>
</template>

<style scoped>
/* finesse · component: order-business-fields · register=product
 * states: content · empty · legacy-fallback · nested-records · raw-json-collapsed
 * tokens: inherited (Element Plus CSS variables) */
.business-fields { box-sizing: border-box; width: 100%; min-width: 0; padding: 16px 18px; border: 1px solid var(--el-border-color-lighter); border-radius: 10px; background: var(--el-fill-color-extra-light); }
.business-fields__header { display: grid; gap: 4px; padding-bottom: 14px; border-bottom: 1px solid var(--el-border-color-lighter); }
.business-fields__header strong { color: var(--el-text-color-primary); font-size: 15px; }
.business-fields__header span { color: var(--el-text-color-secondary); font-size: 12px; line-height: 1.6; }
.business-fields__content { display: grid; gap: 20px; }
.business-fields__header + .business-fields__content { padding-top: 18px; }
.business-section { min-width: 0; }
.business-section h4 { margin: 0 0 10px; color: var(--el-text-color-primary); font-size: 14px; }
.business-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 24px; margin: 0; }
.business-field { display: grid; grid-template-columns: 104px minmax(0, 1fr); gap: 12px; align-items: start; min-width: 0; padding: 9px 0; border-bottom: 1px solid var(--el-border-color-extra-light); }
.business-field--full { grid-column: 1 / -1; }
.business-field dt { color: var(--el-text-color-secondary); font-size: 13px; }
.business-field dd { min-width: 0; margin: 0; overflow-wrap: anywhere; color: var(--el-text-color-regular); line-height: 1.55; }
.business-card-list { display: grid; gap: 10px; }
.business-card { padding: 12px 14px; border: 1px solid var(--el-border-color-lighter); border-radius: 8px; background: var(--el-bg-color); }
.business-card > strong { display: block; margin-bottom: 4px; color: var(--el-text-color-primary); font-size: 13px; }
.business-grid--nested .business-field { padding: 7px 0; }
.business-raw-json { box-sizing: border-box; width: 100%; min-width: 0; max-width: 100%; margin: 20px 0 0; border-top: 1px solid var(--el-border-color-lighter); border-bottom: 0; }
.business-raw-json :deep(.el-collapse-item),
.business-raw-json :deep(.el-collapse-item__header),
.business-raw-json :deep(.el-collapse-item__wrap) { box-sizing: border-box; width: 100%; min-width: 0; max-width: 100%; }
.business-raw-json :deep(.el-collapse-item__header) { padding-inline: 0; }
.business-raw-json :deep(.el-collapse-item__content) { box-sizing: border-box; width: 100%; min-width: 0; max-width: 100%; overflow: hidden; }
.business-raw-json__title { color: var(--el-text-color-regular); font-size: 13px; font-weight: 600; }
.business-raw-json__code { box-sizing: border-box; width: 100%; min-width: 0; max-width: 100%; max-height: 420px; margin: 0; padding: 14px 16px; overflow: auto; border: 1px solid var(--el-border-color-lighter); border-radius: 8px; background: var(--el-fill-color-dark); color: var(--el-text-color-primary); font-family: ui-monospace, SFMono-Regular, Consolas, "Liberation Mono", monospace; font-size: 12px; line-height: 1.65; tab-size: 2; white-space: pre; }
@media (max-width: 640px) {
  .business-fields { padding: 14px; }
  .business-grid { grid-template-columns: 1fr; }
  .business-field--full { grid-column: auto; }
  .business-field { grid-template-columns: 88px minmax(0, 1fr); }
}
</style>
