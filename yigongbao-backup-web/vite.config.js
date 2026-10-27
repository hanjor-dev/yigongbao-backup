import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';

/** Vite 配置：启用 Vue 单文件组件编译，保持本地控制台的轻量部署方式。 */
export default defineConfig({
  plugins: [vue()],
  server: {
    host: '0.0.0.0'
  }
});
