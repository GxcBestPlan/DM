<script setup>
import { computed, onMounted, watch } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import { hasRole, isAdmin, isSupervisor, logout, session } from './session'
import { clearStore, loadSprints, store } from './store'
import BeatStrip from './components/BeatStrip.vue'
import { globalRole, rolesText } from './labels'

const route = useRoute()
const router = useRouter()

const NAV = [
  { to: '/w', label: '工作台', ok: () => true },
  { to: '/pool', label: '需求池', ok: () => hasRole('PM') },
  { to: '/sprints', label: '迭代管理', ok: () => hasRole('DEV_MANAGER') },
  { to: '/board', label: '迭代看板', ok: () => true },
  { to: '/pipeline', label: '管道排期', ok: () => hasRole('DEV_MANAGER') || isSupervisor() },
  { to: '/mine', label: '我的任务', ok: () => hasRole('FRONTEND_DEV', 'BACKEND_DEV', 'DEV_MANAGER') },
  { to: '/testing', label: '测试工作台', ok: () => hasRole('TESTER', 'DEV_MANAGER') },
  { to: '/overview', label: '跨队总览', ok: () => isSupervisor() || isAdmin() },
  { to: '/reports', label: '统计报表', ok: () => hasRole('PM', 'DEV_MANAGER') || isSupervisor() || isAdmin() },
  { to: '/org', label: '组织管理', ok: () => isAdmin() },
]

const nav = computed(() => NAV.filter((item) => item.ok()))
const roleText = computed(() => {
  const user = session.user
  if (!user) return ''
  const roles = rolesText(user.roles)
  return roles || globalRole[user.globalRole] || ''
})

onMounted(() => {
  if (session.user && session.user.teamId) loadSprints()
})

watch(
  () => session.user && session.user.teamId,
  (teamId) => {
    if (teamId) loadSprints(teamId)
  },
)

watch(
  () => route.fullPath,
  () => {
    if (session.user && session.user.teamId && !store.loaded) loadSprints()
  },
)

function signOut() {
  clearStore()
  logout()
  router.push('/login')
}
</script>

<template>
  <RouterView v-if="!session.user" />

  <div v-else class="shell">
    <aside class="rail">
      <RouterLink to="/" class="brand">
        <strong>DM</strong>
        <span>排期台</span>
      </RouterLink>
      <nav class="nav">
        <RouterLink v-for="item in nav" :key="item.to" :to="item.to">{{ item.label }}</RouterLink>
      </nav>
      <div class="rail-foot">
        <div class="who">
          <strong>{{ session.user.name }}</strong>
          <span class="muted small">{{ roleText }}</span>
        </div>
        <button class="link" type="button" @click="signOut">退出</button>
      </div>
    </aside>

    <div class="work">
      <header class="top">
        <div class="ctx">
          <strong>{{ session.user.teamName || '未加入小队' }}</strong>
          <template v-if="store.current">
            <span class="chip tone-live">{{ store.current.name }}</span>
            <span class="muted small">剩余 {{ store.current.daysLeft }} 天</span>
          </template>
          <span v-else class="muted small">暂无进行中的迭代</span>
        </div>
        <BeatStrip
          v-if="store.current"
          class="ctx-strip"
          :start="store.current.startDate"
          :end="store.current.endDate"
          compact
        />
        <div class="account muted small">{{ session.user.account }}</div>
      </header>
      <main class="main">
        <RouterView />
      </main>
    </div>
  </div>
</template>

<style scoped>
.shell { display: flex; min-height: 100%; }

.rail {
  flex: 0 0 204px;
  background: var(--panel);
  border-right: 1px solid var(--rule);
  display: flex;
  flex-direction: column;
  position: sticky;
  top: 0;
  height: 100vh;
}
.brand {
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding: 18px 16px 14px;
  border-bottom: 1px solid var(--rule-soft);
}
.brand strong { font-size: 20px; letter-spacing: -.03em; }
.brand span { font-size: 12.5px; color: var(--ink-3); }
.nav { display: flex; flex-direction: column; padding: 10px 8px; gap: 1px; }
.nav a { padding: 7px 10px; border-radius: var(--r); font-size: 13.5px; color: var(--ink-2); }
.nav a:hover { background: #f2f6f8; color: var(--ink); }
.nav a.router-link-active { background: var(--ink); color: #fff; }
.rail-foot {
  margin-top: auto;
  padding: 12px 16px 16px;
  border-top: 1px solid var(--rule-soft);
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 8px;
}
.rail-foot .who { display: flex; flex-direction: column; line-height: 1.4; }

.work { flex: 1; min-width: 0; display: flex; flex-direction: column; }
.top {
  position: sticky;
  top: 0;
  z-index: 20;
  display: flex;
  align-items: center;
  gap: 18px;
  padding: 10px 22px;
  background: var(--panel);
  border-bottom: 1px solid var(--rule);
}
.ctx { display: flex; align-items: center; gap: 10px; }
.ctx-strip { flex: 0 0 240px; }
.account { margin-left: auto; }
.main { flex: 1; min-width: 0; }

@media (max-width: 900px) {
  .shell { flex-direction: column; }
  .rail { position: static; height: auto; flex: none; }
  .nav { flex-direction: row; flex-wrap: wrap; }
  .ctx-strip { display: none; }
}
</style>
