import { defineConfig, loadEnv } from 'vite';
import vue from '@vitejs/plugin-vue';

/**
 * Vite 配置：启用 Vue 单文件组件编译，并将本地开发时的 /api 请求转发到备份后端。
 * 生产环境可通过同源部署或反向代理提供同样的 /api 路径；开发环境默认使用 18080 端口。
 */
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  const backendUrl = env.VITE_BACKEND_URL || 'http://127.0.0.1:18080';

  return {
    plugins: [vue()],
    server: {
      host: '0.0.0.0',
      port: Number(env.VITE_PORT || 5173),
      strictPort: true,
      proxy: {
        '/api': {
          target: backendUrl,
          changeOrigin: true
        }
      }
    },
    preview: {
      host: '0.0.0.0',
      port: Number(env.VITE_PREVIEW_PORT || 4173),
      strictPort: true,
      proxy: {
        '/api': {
          target: backendUrl,
          changeOrigin: true
        }
      }
    }
  };
});
