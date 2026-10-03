<script setup>
import { computed, onMounted, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { get } from '../api'
import { session } from '../session'
import { loadSprints, store } from '../store'
import BeatStrip from '../components/BeatStrip.vue'
import StatusChip from '../components/StatusChip.vue'
import { boardColumns, requirementStatus, requirementType, taskStatus } from '../labels'

const route = useRoute()
const detail = ref(null)
const sprintId = ref(route.query.sprint ? Number(route.query.sprint) : null)
const assignee = ref('')
const error = ref('')
const loading = ref(true)

const teamId = computed(() => (route.query.team ? Number(route.query.team) : session.user.teamId || null))
const sprint = computed(() => (detail.value ? detail.value.sprint : null))
const requirements = computed(() => (detail.value ? detail.value.requirements : []))

const people = computed(() => {
  const map = new Map()
  requirements.value.forEach((row) =>
    (row.tasks || []).forEach((task) => {
      if (task.assigneeId) map.set(task.assigneeId, task.assigneeName || `#${task.assigneeId}`)
    }),
  )
  return [...map.entries()].map(([id, name]) => ({ id, name }))
})

const visible = computed(() =>
  requirements.value.filter(
    (row) => !assignee.value || (row.tasks || []).some((t) => String(t.assigneeId) === String(assignee.value)),
  ),
)

function column(code) {
  return visible.value.filter((row) => row.status === code)
}

async function loadDetail() {
  loading.value = true
  error.value = ''
  try {
    detail.value = sprintId.value ? await get(`/sprints/${sprintId.value}`) : null
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

async function loadList() {
  try {
    await loadSprints(teamId.value)
  } catch {
    /* 顶栏已提示 */
  }
  if (!sprintId.value) {
    sprintId.value = store.current ? store.current.id : store.sprints[0] ? store.sprints[0].id : null
  }
  await loadDetail()
}

onMounted(loadList)
</script>

<template>
  <div class="page">
    <div class="head">
      <h1>迭代看板</h1>
      <div class="sub">按状态看需求，卡片里的任务是具体的开发单元</div>
      <div class="actions">
        <select v-model="sprintId" style="width: 190px" @change="loadDetail">
          <option :value="null" disabled>选择迭代</option>
          <option v-for="s in store.sprints" :key="s.id" :value="s.id">
            {{ s.name }}（{{ s.status === 'ACTIVE' ? '进行中' : s.status === 'PLANNED' ? '规划中' : '已关闭' }}）
          </option>
        </select>
        <select v-model="assignee" style="width: 150px">
          <option value="">全部成员</option>
          <option v-for="p in people" :key="p.id" :value="String(p.id)">{{ p.name }}</option>
        </select>
      </div>
    </div>

    <p v-if="error" class="err">{{ error }}</p>
    <div v-if="loading" class="panel"><div class="empty">读取中</div></div>
    <div v-else-if="!sprint" class="panel"><div class="empty">这个小队还没有迭代。开发管理者可以在迭代管理里创建。</div></div>

    <template v-else>
      <div class="panel">
        <div class="panel-b board-head">
          <div>
            <div class="sprint-name">{{ sprint.name }}</div>
            <div class="small muted sprint-meta">
              <span>{{ sprint.startDate }} 至 {{ sprint.endDate }}</span>
              <span>剩余 {{ sprint.daysLeft }} 天</span>
              <span>已完成 {{ sprint.doneCount }} / {{ sprint.requirementCount }}</span>
            </div>
          </div>
          <BeatStrip :start="sprint.startDate" :end="sprint.endDate" />
        </div>
      </div>

      <div class="kanban">
        <section v-for="code in boardColumns" :key="code" class="kcol">
          <header class="kcol-h">
            <StatusChip :map="requirementStatus" :value="code" />
            <span class="count">{{ column(code).length }}</span>
          </header>
          <div class="kcol-b">
            <p v-if="!column(code).length" class="kcol-empty">暂无</p>
            <article v-for="row in column(code)" :key="row.id" class="kcard">
              <div class="kcard-h">
                <RouterLink class="title" :to="`/r/${row.id}`">{{ row.title }}</RouterLink>
              </div>
              <div class="kcard-meta">
                <span class="small muted">{{ requirementType[row.type] }}</span>
                <span class="small muted">{{ row.estimate }} 人日</span>
                <span v-if="row.urgent" class="chip tone-risk">插队</span>
                <span v-if="row.blocked" class="chip tone-risk">阻塞</span>
              </div>
              <ul v-if="row.tasks && row.tasks.length" class="tasks">
                <li v-for="task in row.tasks" :key="task.id">
                  <StatusChip :map="taskStatus" :value="task.status" />
                  <span class="task-title">{{ task.title }}</span>
                  <span class="small muted">{{ task.assigneeName || '未分配' }}</span>
                </li>
              </ul>
              <p v-else class="small muted">还没有拆任务</p>
            </article>
          </div>
        </section>
      </div>
    </template>
  </div>
</template>

<style scoped>
.board-head { display: flex; align-items: center; gap: 24px; justify-content: space-between; flex-wrap: wrap; }
.board-head > div:first-child { flex: 1 1 260px; }
.board-head :deep(.strip) { flex: 1 1 420px; }
.sprint-name { font-size: 16px; font-weight: 650; margin-bottom: 3px; }
.sprint-meta { display: flex; gap: 14px; }

.kanban { display: flex; gap: 10px; overflow-x: auto; padding-bottom: 8px; margin-top: 14px; align-items: flex-start; }
.kcol { flex: 0 0 238px; background: #eef2f5; border: 1px solid var(--rule); border-radius: var(--r); }
.kcol-h {
  display: flex; align-items: center; justify-content: space-between;
  padding: 8px 10px; border-bottom: 1px solid var(--rule);
}
.kcol-h .count { font-size: 12px; color: var(--ink-3); }
.kcol-b { padding: 8px; display: flex; flex-direction: column; gap: 8px; min-height: 90px; }
.kcol-empty { font-size: 12.5px; color: var(--ink-3); text-align: center; padding: 10px 0; }
.kcard { background: var(--panel); border: 1px solid var(--rule); border-radius: var(--r); padding: 9px 10px; }
.kcard-h .title { font-size: 13.5px; font-weight: 600; line-height: 1.4; }
.kcard-h .title:hover { color: var(--live); }
.kcard-meta { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; margin-top: 5px; }
.tasks { list-style: none; margin: 8px 0 0; padding: 8px 0 0; border-top: 1px solid var(--rule-soft); display: flex; flex-direction: column; gap: 5px; }
.tasks li { display: flex; align-items: center; gap: 6px; font-size: 12px; }
.task-title { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
</style>
