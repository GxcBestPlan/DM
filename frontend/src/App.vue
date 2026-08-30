<script setup>
import { ref, onMounted } from 'vue'

const backendMessage = ref('')
const error = ref('')
const loading = ref(false)

async function fetchHello() {
  loading.value = true
  error.value = ''
  backendMessage.value = ''
  try {
    const res = await fetch('/api/hello')
    if (!res.ok) throw new Error(`HTTP ${res.status}`)
    backendMessage.value = JSON.stringify(await res.json(), null, 2)
  } catch (e) {
    error.value = `无法连接后端：${e.message}（请确认后端已在 8080 端口启动）`
  } finally {
    loading.value = false
  }
}

onMounted(fetchHello)
</script>

<template>
  <main class="demo">
    <h1>DM · 前后端分离骨架</h1>
    <p class="sub">前端：Vite + Vue 3（npm）｜后端：Spring Boot 2.7（JDK 8）</p>

    <button :disabled="loading" @click="fetchHello">
      {{ loading ? '请求中…' : '调用后端 /api/hello' }}
    </button>

    <pre v-if="backendMessage" class="result">{{ backendMessage }}</pre>
    <p v-if="error" class="err">{{ error }}</p>

    <p class="tip">开发时 Vite 将 /api 代理到 http://localhost:8080（配置见 vite.config.js）</p>
  </main>
</template>

<style scoped>
.demo {
  max-width: 640px;
  margin: 0 auto;
  padding: 3rem 1.5rem;
  font-family: system-ui, -apple-system, 'PingFang SC', 'Microsoft YaHei', sans-serif;
  color: #2c3e50;
}
.sub { color: #7f8c8d; margin-top: -0.5rem; }
button {
  background: #42b883;
  color: #fff;
  border: none;
  padding: 0.6rem 1.2rem;
  border-radius: 6px;
  font-size: 1rem;
  cursor: pointer;
}
button:disabled { opacity: 0.6; cursor: wait; }
.result {
  background: #f6f8fa;
  border: 1px solid #e1e4e8;
  border-radius: 8px;
  padding: 1rem;
  margin-top: 1.2rem;
  font-size: 0.9rem;
  overflow-x: auto;
}
.err { color: #e74c3c; }
.tip { color: #95a5a6; font-size: 0.85rem; }
</style>
