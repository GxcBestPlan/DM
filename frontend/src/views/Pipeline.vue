<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { get, put } from '../api'
import { hasRole, isAdmin, session } from '../session'
import { loadSprints, store } from '../store'

const route = useRoute()
const sprintId = ref(route.query.sprint ? Number(route.query.sprint) : null)
const data = ref(null)
const error = ref('')
const loading = ref(true)
const saving = ref(false)
const dragging = ref(null)

const DOW = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']

const canManage = computed(() => isAdmin() || hasRole('DEV_MANAGER'))
const teamId = computed(() => (route.query.team ? Number(route.query.team) : session.user.teamId || null))
const days = computed(() => (data.value ? data.value.days : []))
const people = computed(() => (data.value ? data.value.people : []))
const conflictSet = computed(() => {
  const set = new Set()
  const rows = data.value && data.value.conflicts ? data.value.conflicts : []
  rows.forEach((row) => set.add(`${row.userId}|${row.date}`))
  return set
})
const conflictRows = computed(() => {
  const rows = data.value && data.value.conflicts ? data.value.conflicts : []
  const names = new Map(people.value.map((p) => [p.userId, p.name]))
  return rows.map((row) => ({
    key: `${row.userId}|${row.date}`,
    text: `${names.get(row.userId) || `#${row.userId}`} 在 ${row.date} 有 ${row.taskIds.length} 个任务重叠`,
  }))
})

function weekday(iso) {
  return DOW[new Date(`${iso}T00:00:00`).getDay()]
}
function isWeekend(iso) {
  const day = new Date(`${iso}T00:00:00`).getDay()
  return day === 0 || day === 6
}
function chipsFor(person, day) {
  return (person.tasks || []).filter((task) => task.plannedStartDate <= day && day <= task.plannedEndDate)
}
function addDays(iso, count) {
  const date = new Date(`${iso}T00:00:00`)
  date.setDate(date.getDate() + count)
  const m = String(date.getMonth() + 1).padStart(2, '0')
  const d = String(date.getDate()).padStart(2, '0')
  return `${date.getFullYear()}-${m}-${d}`
}
function spanOf(task) {
  if (!task.plannedStartDate || !task.plannedEndDate) return 1
  const from = new Date(`${task.plannedStartDate}T00:00:00`)
  const to = new Date(`${task.plannedEndDate}T00:00:00`)
  return Math.max(1, Math.round((to - from) / 86400000) + 1)
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    await loadSprints(teamId.value)
    if (!sprintId.value) {
      sprintId.value = store.current ? store.current.id : store.sprints[0] ? store.sprints[0].id : null
    }
    data.value = sprintId.value ? await get(`/sprints/${sprintId.value}/pipeline`) : null
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

onMounted(load)

function onDragStart(event, task) {
  if (!canManage.value) return
  dragging.value = task
  event.dataTransfer.effectAllowed = 'move'
}

async function onDrop(person, day) {
  if (!dragging.value || !canManage.value || saving.value) return
  const task = dragging.value
  const sprintEnd = data.value.sprint.endDate
  let end = addDays(day, spanOf(task) - 1)
  if (end > sprintEnd) end = sprintEnd
  saving.value = true
  error.value = ''
  try {
    await put(`/tasks/${task.id}/schedule`, {
      assigneeId: person.userId,
      plannedStartDate: day,
      plannedEndDate: end,
    })
    dragging.value = null
    await load()
  } catch (e) {
    error.value = e.message
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div class="page">
    <div class="head">
      <h1>管道排期</h1>
      <div class="sub">人员 × 日期。把任务拖到某人的某一天即可调整安排</div>
      <div class="actions">
        <select v-model="sprintId" style="width: 190px" @change="load">
          <option :value="null" disabled>选择迭代</option>
          <option v-for="s in store.sprints" :key="s.id" :value="s.id">
            {{ s.name }}（{{ s.status === 'ACTIVE' ? '进行中' : s.status === 'PLANNED' ? '规划中' : '已关闭' }}）
          </option>
        </select>
      </div>
    </div>

    <p v-if="error" class="err">{{ error }}</p>
    <div v-if="loading" class="panel"><div class="empty">读取中</div></div>
    <div v-else-if="!data" class="panel"><div class="empty">先创建迭代，再把需求排进来。</div></div>

    <template v-else>
      <div class="legend">
        <span class="chip">正常</span>
        <span class="chip tone-risk">插队</span>
        <span class="chip tone-done">已完成</span>
        <span class="small muted">红框 = 同一人同一天有多个任务重叠</span>
        <span v-if="!canManage" class="small muted">当前角色只读，不能拖拽调整</span>
      </div>

      <div v-if="conflictRows.length" class="panel conflict-note">
        <div class="panel-b">
          <strong>发现 {{ conflictRows.length }} 处重叠</strong>
          <ul>
            <li v-for="row in conflictRows" :key="row.key" class="small">{{ row.text }}</li>
          </ul>
          <p class="small muted">重叠只做提示。要不要顺延其他需求，由开发管理者决定。</p>
        </div>
      </div>

      <div class="grid-wrap">
        <table class="pipe">
          <thead>
            <tr>
              <th class="who">人员</th>
              <th v-for="day in days" :key="day" :class="{ weekend: isWeekend(day) }">
                <span class="dow">{{ weekday(day) }}</span>
                <span class="dom">{{ day.slice(5) }}</span>
              </th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="person in people" :key="person.userId">
              <th class="who">
                {{ person.name }}
                <div class="small muted">{{ (person.tasks || []).length }} 个任务</div>
              </th>
              <td
                v-for="day in days"
                :key="day"
                class="cell"
                :class="{
                  weekend: isWeekend(day),
                  conflict: conflictSet.has(`${person.userId}|${day}`),
                  droppable: canManage,
                }"
                @dragover.prevent
                @drop="onDrop(person, day)"
              >
                <span
                  v-for="task in chipsFor(person, day)"
                  :key="task.id"
                  class="task"
                  :class="{ urgent: task.requirementUrgent, done: task.status === 'DONE', blocked: task.blocked }"
                  :draggable="canManage"
                  :title="`${task.requirementTitle} · ${task.title} · ${task.status}`"
                  @dragstart="onDragStart($event, task)"
                >
                  {{ task.title }}
                </span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="panel">
        <div class="panel-h">未排期任务</div>
        <div class="panel-b">
          <p v-if="!data.unplanned.length" class="muted small">所有任务都已排到人、到天。</p>
          <template v-else>
            <span
              v-for="task in data.unplanned"
              :key="task.id"
              class="task pool-chip"
              :draggable="canManage"
              :title="`${task.requirementTitle} · ${task.title}`"
              @dragstart="onDragStart($event, task)"
            >
              {{ task.title }}
            </span>
            <p class="small muted">拖到上方任意人员、任意一天即可排期。</p>
          </template>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.legend { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-bottom: 12px; }
.conflict-note { border-color: #e3bdb9; background: #fdf6f5; }
.conflict-note strong { color: var(--risk); }
.conflict-note ul { margin: 6px 0; padding-left: 18px; }

.grid-wrap { overflow-x: auto; background: var(--panel); border: 1px solid var(--rule); border-radius: var(--r); }
.pipe { border-collapse: collapse; font-size: 12px; min-width: 900px; }
.pipe th, .pipe td { border: 1px solid var(--rule-soft); padding: 4px 5px; }
.pipe thead th { background: #f4f7f9; text-align: center; color: var(--ink-2); font-weight: 600; }
.pipe thead th.weekend { background: #eceff1; color: var(--ink-3); }
.pipe .dow { display: block; font-size: 11px; color: var(--ink-3); }
.pipe .dom { display: block; font-size: 12px; }
.pipe th.who {
  position: sticky; left: 0; background: var(--panel); text-align: left;
  min-width: 104px; padding: 6px 10px; font-weight: 650;
}
.pipe td.cell { height: 52px; min-width: 84px; vertical-align: top; }
.pipe td.weekend { background: #f6f7f8; }
.pipe td.droppable:hover { background: #eef6f8; }
.pipe td.conflict { background: #fdecea; box-shadow: inset 0 0 0 2px #d1655c; }

.task {
  display: block; margin: 1px 0; padding: 1px 5px; border-radius: 1px;
  background: #e4eef9; color: #1f4f7a; font-size: 11px; line-height: 1.6;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap; cursor: grab;
}
.task.urgent { background: #fbe3e0; color: var(--risk); }
.task.done { background: #e2f1e9; color: var(--done); }
.task.blocked { outline: 1px dashed var(--risk); }
.pool-chip { display: inline-block; width: auto; margin: 0 6px 6px 0; }
</style>
