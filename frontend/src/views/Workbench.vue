<script setup>
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { get } from '../api'
import { hasRole, isAdmin, isSupervisor, session } from '../session'
import { loadSprints, store } from '../store'
import BeatStrip from '../components/BeatStrip.vue'
import StatusChip from '../components/StatusChip.vue'
import TaskActions from '../components/TaskActions.vue'
import { requirementStatus, taskStatus } from '../labels'

const today = ref([])
const error = ref('')
const loading = ref(true)

async function load() {
  loading.value = true
  error.value = ''
  try {
    if (session.user.teamId) await loadSprints()
    today.value = (await get('/tasks/mine?scope=today')) || []
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

onMounted(load)

const current = computed(() => store.current)
const greeting = computed(() => {
  const hour = new Date().getHours()
  if (hour < 6) return '还在赶版本'
  if (hour < 12) return '早上好'
  if (hour < 18) return '下午好'
  return '晚上好'
})
const doneRate = computed(() => {
  if (!current.value || !current.value.requirementCount) return null
  return Math.round((current.value.doneCount / current.value.requirementCount) * 100)
})

const shortcuts = computed(() => {
  const list = []
  if (hasRole('PM')) list.push({ to: '/pool', label: '去需求池' })
  if (hasRole('DEV_MANAGER')) list.push({ to: '/pipeline', label: '去管道排期' }, { to: '/sprints', label: '管理迭代' })
  if (hasRole('TESTER')) list.push({ to: '/testing', label: '去测试工作台' })
  if (isSupervisor() || isAdmin()) list.push({ to: '/overview', label: '看跨队总览' })
  list.push({ to: '/board', label: '看迭代看板' })
  return list
})
</script>

<template>
  <div class="page">
    <div class="head">
      <h1>{{ greeting }}，{{ session.user.name }}</h1>
      <div class="sub">
        <span>{{ session.user.teamName || '尚未加入小队' }}</span>
        <span v-if="current">当前迭代 {{ current.name }}</span>
      </div>
      <div class="actions">
        <RouterLink v-for="s in shortcuts" :key="s.to" class="btn sm" :to="s.to">{{ s.label }}</RouterLink>
      </div>
    </div>

    <p v-if="error" class="err">{{ error }}</p>

    <div class="panel">
      <div class="ledger">
        <div class="cell"><span class="k">今日待办</span><span class="v">{{ today.length }}</span></div>
        <div class="cell"><span class="k">当前迭代</span><span class="v">{{ current ? current.name : '—' }}</span></div>
        <div class="cell"><span class="k">迭代需求</span><span class="v">{{ current ? current.requirementCount : '—' }}</span></div>
        <div class="cell"><span class="k">已完成</span><span class="v">{{ current ? current.doneCount : '—' }}</span></div>
        <div class="cell"><span class="k">剩余天数</span><span class="v">{{ current ? current.daysLeft : '—' }}</span></div>
      </div>
    </div>

    <div class="panel">
      <div class="panel-h">今天该做的</div>
      <div v-if="loading" class="empty">读取中</div>
      <div v-else-if="!today.length" class="empty">
        今天没有指派给你的任务。需要调整分工，找开发管理者在管道排期里改。
      </div>
      <div v-else class="panel-b tight">
        <table class="table">
          <thead>
            <tr>
              <th>需求</th>
              <th>任务</th>
              <th>状态</th>
              <th>计划日期</th>
              <th>更新</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="task in today" :key="task.id">
              <td>
                <RouterLink class="title" :to="`/r/${task.requirementId}`">{{ task.requirementTitle }}</RouterLink>
                <div class="small muted">{{ task.sprintName }}</div>
              </td>
              <td>{{ task.title }}</td>
              <td>
                <StatusChip :map="taskStatus" :value="task.status" />
                <span v-if="task.blocked" class="chip tone-risk ml">阻塞</span>
              </td>
              <td class="small muted nowrap">
                {{ task.plannedStartDate || '未排' }}<template v-if="task.plannedEndDate"> 至 {{ task.plannedEndDate }}</template>
              </td>
              <td><TaskActions :task="task" @changed="load" /></td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <div v-if="current" class="panel">
      <div class="panel-h">
        迭代进度
        <div class="actions">
          <RouterLink class="btn sm" to="/board">看板</RouterLink>
        </div>
      </div>
      <div class="panel-b">
        <BeatStrip :start="current.startDate" :end="current.endDate" />
        <div class="progress">
          <div class="bar"><span :style="{ width: `${doneRate === null ? 0 : doneRate}%` }" /></div>
          <div class="small muted">
            已验收 {{ current.doneCount }} / {{ current.requirementCount }}
            <template v-if="doneRate !== null">（{{ doneRate }}%）</template>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.ledger { display: flex; flex-wrap: wrap; }
.head .sub { display: flex; gap: 14px; }
.ledger .cell {
  flex: 1 1 140px;
  padding: 12px 16px;
  border-right: 1px solid var(--rule-soft);
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.ledger .cell:last-child { border-right: 0; }
.ledger .k { font-size: 12px; color: var(--ink-3); }
.ledger .v { font-size: 18px; font-weight: 650; letter-spacing: -.01em; }
.progress { margin-top: 12px; display: flex; flex-direction: column; gap: 6px; }
.ml { margin-left: 6px; }
</style>
