<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { login } from '../session'
import BeatStrip from '../components/BeatStrip.vue'

const router = useRouter()
const account = ref('')
const password = ref('')
const error = ref('')
const busy = ref(false)

function fmt(date) {
  const m = String(date.getMonth() + 1).padStart(2, '0')
  const d = String(date.getDate()).padStart(2, '0')
  return `${date.getFullYear()}-${m}-${d}`
}

/** 登录页展示的示例周期：本周一开始的两周。 */
const range = (() => {
  const now = new Date()
  const shift = (now.getDay() + 6) % 7
  const monday = new Date(now.getFullYear(), now.getMonth(), now.getDate() - shift)
  const end = new Date(monday.getFullYear(), monday.getMonth(), monday.getDate() + 13)
  return { start: fmt(monday), end: fmt(end) }
})()

const progress = ref(0)

onMounted(() => {
  if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
    progress.value = 1
    return
  }
  const timer = setInterval(() => {
    progress.value = Math.min(1, progress.value + 0.1)
    if (progress.value >= 1) clearInterval(timer)
  }, 80)
})

async function submit() {
  busy.value = true
  error.value = ''
  try {
    await login(account.value.trim(), password.value)
    router.push('/')
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="entry">
    <section class="identity">
      <div class="mark"><strong>DM</strong><span>排期台</span></div>
      <h1>两周一个版本<br />排到人，也排到天</h1>
      <p class="lede">
        需求池、迭代排期、每日进度、测试验收。原先散在多张 Excel 里的东西，收进一张随时能看的调度台。
      </p>
      <div class="hero">
        <BeatStrip :start="range.start" :end="range.end" dark :progress="progress" />
        <p class="hint">一个迭代就是十个工作日。排期、负载和进度都落在这条轴上。</p>
      </div>
    </section>

    <section class="form">
      <form @submit.prevent="submit">
        <h2>登录</h2>
        <label class="field">
          <span>账号</span>
          <input v-model="account" autocomplete="username" placeholder="请输入账号" />
        </label>
        <label class="field">
          <span>密码</span>
          <input v-model="password" type="password" autocomplete="current-password" placeholder="请输入密码" />
        </label>
        <p v-if="error" class="err">{{ error }}</p>
        <button class="btn primary wide" type="submit" :disabled="busy">
          {{ busy ? '正在登录' : '登录' }}
        </button>
        <p class="muted small">账号由系统管理员创建。忘记密码请联系管理员重置。</p>
      </form>
    </section>
  </div>
</template>

<style scoped>
.entry {
  display: grid;
  grid-template-columns: minmax(0, 1.1fr) minmax(360px, 0.9fr);
  min-height: 100vh;
}

.identity {
  background: var(--ink);
  color: #e9f0f4;
  padding: 56px 52px;
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 22px;
}
.mark { display: flex; align-items: baseline; gap: 8px; }
.mark strong { font-size: 22px; letter-spacing: -.03em; }
.mark span { font-size: 12.5px; color: #8ea3b1; }
.identity h1 { font-size: clamp(28px, 3.2vw, 42px); line-height: 1.14; letter-spacing: -.035em; }
.lede { max-width: 44ch; color: #b9c8d2; font-size: 14.5px; }
.hero { max-width: 580px; margin-top: 10px; }
.hint { margin-top: 12px; font-size: 12.5px; color: #8ea3b1; }

.form { display: flex; align-items: center; justify-content: center; padding: 40px 24px; }
.form form { width: 100%; max-width: 320px; }
.form h2 { font-size: 18px; margin-bottom: 18px; }
.wide { width: 100%; justify-content: center; padding: 8px; margin: 6px 0 12px; }

@media (max-width: 860px) {
  .entry { grid-template-columns: 1fr; }
  .identity { padding: 36px 24px; }
  .form { padding: 28px 24px 48px; }
}
</style>
