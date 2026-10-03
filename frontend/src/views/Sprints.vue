<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { get, post } from '../api'
import { hasRole, isAdmin } from '../session'
import { loadSprints, store } from '../store'
import StatusChip from '../components/StatusChip.vue'
import { sprintStatus } from '../labels'

const pool = ref([])
const error = ref('')
const busy = ref(false)
const loading = ref(true)
const selected = ref([])
const targetSprintId = ref('')
const create = reactive({ name: '', startDate: '', endDate: '' })
const showCreate = ref(false)

const canManage = computed(() => isAdmin() || hasRole('DEV_MANAGER'))
const plannedSprints = computed(() => store.sprints.filter((s) => s.status === 'PLANNED'))
const activeSprint = computed(() => store.sprints.find((s) => s.status === 'ACTIVE') || null)
const reviewed = computed(() => pool.value.filter((r) => r.status === 'REVIEWED'))
const current = computed(() => store.current)

async function load() {
  loading.value = true
  error.value = ''
  try {
    await loadSprints()
    pool.value = (await get('/requirements')) || []
    if (!targetSprintId.value && plannedSprints.value.length) {
      targetSprintId.value = String(plannedSprints.value[0].id)
    }
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

onMounted(load)

async function act(path, label) {
  busy.value = true
  error.value = ''
  try {
    await post(path)
    await load()
  } catch (e) {
    error.value = `${label}失败：${e.message}`
  } finally {
    busy.value = false
  }
}

async function createSprint() {
  busy.value = true
  error.value = ''
  try {
    await post('/sprints', { ...create })
    showCreate.value = false
    Object.assign(create, { name: '', startDate: '', endDate: '' })
    store.loaded = false
    await load()
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = false
  }
}

async function scheduleSelected() {
  busy.value = true
  error.value = ''
  try {
    for (const id of selected.value) {
      await post(`/requirements/${id}/schedule`, { sprintId: Number(targetSprintId.value), urgent: false })
    }
    selected.value = []
    await load()
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = false
  }
}

async function interrupt(requirement) {
  if (
    !window.confirm(
      `把「${requirement.title}」插队到 ${activeSprint.value.name}？\n插队只做提示，不会自动顺延其他需求；排完请到管道排期确认是否产生同日重叠。`,
    )
  )
    return
  busy.value = true
  error.value = ''
  try {
    await post(`/requirements/${requirement.id}/schedule`, { sprintId: activeSprint.value.id, urgent: true })
    await load()
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = false
  }
}

function toggle(id) {
  const index = selected.value.indexOf(id)
  if (index >= 0) selected.value.splice(index, 1)
  else selected.value.push(id)
}
</script>

<template>
  <div class="page">
    <div class="head">
      <h1>迭代管理</h1>
      <div class="sub">创建迭代、排入需求、开始与关闭</div>
      <div class="actions">
        <button class="btn primary" @click="showCreate = !showCreate">新建迭代</button>
      </div>
    </div>

    <p v-if="error" class="err">{{ error }}</p>

    <div v-if="showCreate" class="panel">
      <div class="panel-h">新建迭代</div>
      <div class="panel-b">
        <div class="row">
          <label class="field" style="flex: 1.4">
            <span>迭代名称</span>
            <input v-model="create.name" placeholder="如 V2.3" />
          </label>
          <label class="field" style="flex: 1">
            <span>开始日期</span>
            <input v-model="create.startDate" type="date" />
          </label>
          <label class="field" style="flex: 1">
            <span>结束日期</span>
            <input v-model="create.endDate" type="date" />
          </label>
          <button class="btn primary" :disabled="busy" @click="createSprint">创建</button>
        </div>
      </div>
    </div>

    <div v-if="current" class="panel">
      <div class="panel-h">
        当前迭代
        <div class="actions">
          <RouterLink class="btn sm" :to="`/board?sprint=${current.id}`">看板</RouterLink>
          <RouterLink v-if="canManage" class="btn sm" :to="`/pipeline?sprint=${current.id}`">管道排期</RouterLink>
        </div>
      </div>
      <div class="panel-b">
        <div class="ledger">
          <div class="cell"><span class="k">名称</span><span class="v">{{ current.name }}</span></div>
          <div class="cell"><span class="k">起止</span><span class="v small">{{ current.startDate }} 至 {{ current.endDate }}</span></div>
          <div class="cell"><span class="k">状态</span><span class="v"><StatusChip :map="sprintStatus" :value="current.status" /></span></div>
          <div class="cell"><span class="k">剩余</span><span class="v">{{ current.daysLeft }} 天</span></div>
          <div class="cell"><span class="k">需求 / 已完成</span><span class="v">{{ current.requirementCount }} / {{ current.doneCount }}</span></div>
        </div>
        <div class="row" style="margin-top: 12px">
          <button v-if="current.status === 'PLANNED'" class="btn primary" :disabled="busy" @click="act(`/sprints/${current.id}/start`, '开始迭代')">
            开始迭代
          </button>
          <button v-if="current.status === 'ACTIVE'" class="btn danger" :disabled="busy" @click="act(`/sprints/${current.id}/close`, '关闭迭代')">
            关闭迭代
          </button>
          <button v-if="current.status === 'CLOSED'" class="btn" :disabled="busy" @click="act(`/sprints/${current.id}/reopen`, '重新打开')">
            重新打开
          </button>
          <span class="small muted">关闭后需求与任务只读，需要变更先重新打开。</span>
        </div>
      </div>
    </div>

    <div class="panel">
      <div class="panel-h">排入需求</div>
      <div v-if="loading" class="empty">读取中</div>
      <div v-else-if="!reviewed.length" class="empty">需求池里还没有已评审的需求。先让产品经理完成评审。</div>
      <template v-else>
        <div class="panel-b tight">
          <table class="table">
            <thead>
              <tr>
                <th style="width: 40px"></th>
                <th>标题</th>
                <th class="num" style="width: 90px">预估人日</th>
                <th class="num" style="width: 80px">池内顺序</th>
                <th style="width: 110px">插队</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in reviewed" :key="row.id">
                <td><input type="checkbox" :checked="selected.includes(row.id)" @change="toggle(row.id)" /></td>
                <td>
                  <RouterLink class="title" :to="`/r/${row.id}`">{{ row.title }}</RouterLink>
                </td>
                <td class="num">{{ row.estimate }}</td>
                <td class="num muted">{{ row.poolSeq }}</td>
                <td>
                  <button v-if="activeSprint" class="btn sm" :disabled="busy" @click="interrupt(row)">插队</button>
                  <span v-else class="muted small">无进行中迭代</span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <div class="panel-b row">
          <select v-model="targetSprintId" style="width: 200px">
            <option value="" disabled>选择目标迭代</option>
            <option v-for="s in plannedSprints" :key="s.id" :value="String(s.id)">{{ s.name }}（规划中）</option>
          </select>
          <button class="btn primary" :disabled="busy || !selected.length || !targetSprintId" @click="scheduleSelected">
            排入选中需求
          </button>
          <span class="small muted">需要插队时，用右侧「插队」按钮排入进行中的迭代。</span>
        </div>
      </template>
    </div>

    <div class="panel">
      <div class="panel-h">历史迭代</div>
      <div v-if="!store.sprints.length" class="empty">还没有创建过迭代。</div>
      <table v-else class="table">
        <thead>
          <tr>
            <th>名称</th>
            <th>起止</th>
            <th style="width: 90px">状态</th>
            <th class="num" style="width: 110px">需求 / 完成</th>
            <th style="width: 180px">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="s in store.sprints" :key="s.id">
            <td class="title">{{ s.name }}</td>
            <td class="small muted">{{ s.startDate }} 至 {{ s.endDate }}</td>
            <td><StatusChip :map="sprintStatus" :value="s.status" /></td>
            <td class="num">{{ s.requirementCount }} / {{ s.doneCount }}</td>
            <td class="nowrap">
              <RouterLink class="btn sm" :to="`/board?sprint=${s.id}`">看板</RouterLink>
              <RouterLink v-if="canManage" class="btn sm" :to="`/reports?sprint=${s.id}`">报告</RouterLink>
              <button v-if="s.status === 'PLANNED'" class="btn sm primary" :disabled="busy" @click="act(`/sprints/${s.id}/start`, '开始迭代')">
                开始
              </button>
              <button v-if="s.status === 'ACTIVE'" class="btn sm danger" :disabled="busy" @click="act(`/sprints/${s.id}/close`, '关闭迭代')">
                关闭
              </button>
              <button v-if="s.status === 'CLOSED'" class="btn sm" :disabled="busy" @click="act(`/sprints/${s.id}/reopen`, '重新打开')">
                重新打开
              </button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<style scoped>
.ledger { display: flex; flex-wrap: wrap; margin: -4px 0 0; }
.ledger .cell {
  flex: 1 1 150px;
  padding: 8px 16px 8px 0;
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.ledger .k { font-size: 12px; color: var(--ink-3); }
.ledger .v { font-size: 15px; font-weight: 600; }
</style>
