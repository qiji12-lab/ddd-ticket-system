import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    port: 5173,
    proxy: {
      // 开发环境把 /api 代理到 Spring Boot，前端代码里统一写相对路径，无需处理 CORS
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
})
