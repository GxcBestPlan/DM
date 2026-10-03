<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { get, post, put } from '../api'
import { loadSprints, store } from '../store'
import StatusChip from '../components/StatusChip.vue'
import { requirementStatus, requirementType } from '../labels'

const rows = ref([])
const loading = ref(true)
const error = ref('')
const busy = ref(false)

const filters = reactive({ keyword: '', type: '', status: '', createdBy: '' })
const dialog = reactive({ kind: '', row: null })
const form = reactive({
  title: '',
  type: 'FEATURE',
  estimate: '1',
  description: '',
  acceptanceCriteria: '',
  externalNote: '',
})
const review = reactive({ comment: '' })
const schedule = reactive({ sprintId: '' })

const filtered = computed(() =>
  rows.value.filter(
    (row) =>
      (!filters.keyword || row.title.toLowerCase().includes(filters.keyword.trim().toLowerCase())) &&
      (!filters.type || row.type === filters.type) &&
      (!filters.status || row.status === filters.status) &&
      (!filters.createdBy || String(row.createdBy) === String(filters.createdBy)),
  ),
)

const creators = computed(() => {
  const map = new Map()
  rows.value.forEach((row) => map.set(row.createdBy, row.createdByName || `#${row.createdBy}`))
  return [...map.entries()].map(([id, name]) => ({ id, name }))
})

const orderLocked = computed(() => !!(filters.keyword || filters.type || filters.status || filters.createdBy))
const selectedSprint = computed(
  () => store.sprints.find((s) => String(s.id) === String(schedule.sprintId)) || null,
)
const missingAcceptance = computed(() => dialog.row && !dialog.row.acceptanceCriteria)
const poolEmpty = computed(() => !loading.value && !rows.value.length)

async function load() {
  loading.value = true
  error.value = ''
  try {
    await loadSprints()
    rows.value = (await get('/requirements')) || []
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

onMounted(load)

function openCreate() {
  dialog.kind = 'edit'
  dialog.row = null
  Object.assign(form, {
    title: '',
    type: 'FEATURE',
    estimate: '1',
    description: '',
    acceptanceCriteria: '',
    externalNote: '',
  })
}

function openEdit(row) {
  dialog.kind = 'edit'
  dialog.row = row
  Object.assign(form, {
    title: row.title,
    type: row.type,
    estimate: String(row.estimate),
    description: row.description || '',
    acceptanceCriteria: row.acceptanceCriteria || '',
    externalNote: row.externalNote || '',
  })
}

function openReview(row) {
  dialog.kind = 'review'
  dialog.row = row
  review.comment = ''
}

function openSchedule(row) {
  dialog.kind = 'schedule'
  dialog.row = row
  const planned = store.sprints.find((s) => s.status === 'PLANNED')
  const target = planned || store.current
  schedule.sprintId = target ? String(target.id) : ''
}

function close() {
  dialog.kind = ''
  dialog.row = null
}

async function save() {
  busy.value = true
  error.value = ''
  const body = {
    title: form.title,
    type: form.type,
    estimate: Number(form.estimate),
    description: form.description,
    acceptanceCriteria: form.acceptanceCriteria,
    externalNote: form.externalNote,
  }
  try {
    if (dialog.row) await put(`/requirements/${dialog.row.id}`, body)
    else await post('/requirements', body)
    close()
    await load()
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = false
  }
}

async function submitReview(approved) {
  busy.value = true
  error.value = ''
  try {
    await post(`/requirements/${dialog.row.id}/review`, { approved, comment: review.comment })
    close()
    await load()
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = false
  }
}

async function submitSchedule() {
  busy.value = true
  error.value = ''
  try {
    await post(`/requirements/${dialog.row.id}/schedule`, {
      sprintId: Number(schedule.sprintId),
      urgent: !!(selectedSprint.value && selectedSprint.value.status === 'ACTIVE'),
    })
    close()
    await load()
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = false
  }
}

async function cancelRequirement(row) {
  if (!window.confirm(`取消需求「${row.title}」？取消后不再出现在需求池。`)) return
  try {
    await post(`/requirements/${row.id}/cancel`)
    await load()
  } catch (e) {
    error.value = e.message
  }
}

const dragIndex = ref(-1)

async function saveOrder() {
  try {
    await put('/requirements/pool-order', { ids: rows.value.map((row) => row.id) })
  } catch (e) {
    error.value = e.message
    await load()
  }
}

function onDrop(index) {
  if (dragIndex.value < 0 || dragIndex.value === index) return
  const list = [...rows.value]
  const [moved] = list.splice(dragIndex.value, 1)
  list.splice(index, 0, moved)
  rows.value = list
  dragIndex.value = -1
  saveOrder()
}

function move(index, delta) {
  const target = index + delta
  if (target < 0 || target >= rows.value.length) return
  const list = [...rows.value]
  const [moved] = list.splice(index, 1)
  list.splice(target, 0, moved)
  rows.value = list
  saveOrder()
}
</script>

<template>
  <div class="page">
    <div class="head">
      <h1>需求池</h1>
      <div class="sub">排序决定排期取用顺序，一个迭代从这里挑需求</div>
      <div class="actions">
        <input v-model="filters.keyword" placeholder="搜索标题" style="width: 160px" />
        <select v-model="filters.type" style="width: 118px">
          <option value="">全部类型</option>
          <option v-for="(name, code) in requirementType" :key="code" :value="code">{{ name }}</option>
        </select>
        <select v-model="filters.status" style="width: 118px">
          <option value="">全部状态</option>
          <option value="PENDING_REVIEW">待评审</option>
          <option value="REVIEWED">已评审</option>
        </select>
        <select v-model="filters.createdBy" style="width: 126px">
          <option value="">全部创建人</option>
          <option v-for="c in creators" :key="c.id" :value="String(c.id)">{{ c.name }}</option>
        </select>
        <button class="btn primary" @click="openCreate">录入需求</button>
      </div>
    </div>

    <p v-if="error" class="err">{{ error }}</p>
    <p v-if="orderLocked" class="muted small">筛选状态下不能调整顺序。清空筛选后可拖拽排序。</p>

    <div class="panel">
      <div v-if="loading" class="empty">读取中</div>
      <div v-else-if="poolEmpty" class="empty">需求池是空的。录入第一条需求，或让产品经理先评审已有需求。</div>
      <div v-else-if="!filtered.length" class="empty">没有符合筛选条件的需求。</div>
      <table v-else class="table">
        <thead>
          <tr>
            <th style="width: 74px">顺序</th>
            <th>标题</th>
            <th style="width: 96px">类型</th>
            <th style="width: 92px">状态</th>
            <th class="num" style="width: 80px">预估人日</th>
            <th style="width: 110px">创建人</th>
            <th style="width: 250px">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="(row, index) in filtered"
            :key="row.id"
            :draggable="!orderLocked"
            @dragstart="dragIndex = index"
            @dragover.prevent
            @drop="onDrop(index)"
          >
            <td class="nowrap">
              <span class="handle" :class="{ off: orderLocked }" title="拖拽调整顺序">⠿</span>
              {{ row.poolSeq }}
              <span v-if="!orderLocked" class="order-btns">
                <button class="link" :disabled="index === 0" @click="move(index, -1)">↑</button>
                <button class="link" :disabled="index === filtered.length - 1" @click="move(index, 1)">↓</button>
              </span>
            </td>
            <td>
              <RouterLink class="title" :to="`/r/${row.id}`">{{ row.title }}</RouterLink>
              <div v-if="row.externalNote" class="small muted">外部依赖：{{ row.externalNote }}</div>
              <div v-if="!row.acceptanceCriteria" class="small muted">验收标准待补</div>
            </td>
            <td class="small">{{ requirementType[row.type] }}</td>
            <td><StatusChip :map="requirementStatus" :value="row.status" /></td>
            <td class="num">{{ row.estimate }}</td>
            <td class="small muted">{{ row.createdByName }}</td>
            <td class="nowrap">
              <button class="btn sm" @click="openEdit(row)">编辑</button>
              <button v-if="row.status === 'PENDING_REVIEW'" class="btn sm primary" @click="openReview(row)">
                登记评审
              </button>
              <button v-if="row.status === 'REVIEWED'" class="btn sm primary" @click="openSchedule(row)">
                排入迭代
              </button>
              <button class="btn sm danger" @click="cancelRequirement(row)">取消</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <div v-if="dialog.kind" class="scrim" @click.self="close">
      <div class="dialog">
        <template v-if="dialog.kind === 'edit'">
          <div class="panel-h">{{ dialog.row ? '编辑需求' : '录入需求' }}</div>
          <div class="panel-b">
            <label class="field"><span>标题</span><input v-model="form.title" placeholder="一句话说清要做什么" /></label>
            <div class="row">
              <label class="field" style="flex: 1">
                <span>类型</span>
                <select v-model="form.type">
                  <option v-for="(name, code) in requirementType" :key="code" :value="code">{{ name }}</option>
                </select>
              </label>
              <label class="field" style="flex: 1">
                <span>预估人日（0.5 的倍数）</span>
                <input v-model="form.estimate" type="number" step="0.5" min="0.5" />
              </label>
            </div>
            <label class="field"><span>描述</span><textarea v-model="form.description" rows="3" /></label>
            <label class="field">
              <span>验收标准（评审通过前必填）</span>
              <textarea v-model="form.acceptanceCriteria" rows="3" placeholder="测试据什么判定通过" />
            </label>
            <label class="field">
              <span>外部依赖 / 跨队协调备注（选填）</span>
              <input v-model="form.externalNote" placeholder="如：依赖基础平台队导出组件 v2" />
            </label>
            <p v-if="error" class="err">{{ error }}</p>
          </div>
          <div class="dialog-foot">
            <button class="btn" @click="close">取消</button>
            <button class="btn primary" :disabled="busy" @click="save">保存</button>
          </div>
        </template>

        <template v-else-if="dialog.kind === 'review'">
          <div class="panel-h">登记评审结果</div>
          <div class="panel-b">
            <p class="small muted">{{ dialog.row.title }}</p>
            <p v-if="missingAcceptance" class="err">这条需求还没有验收标准，通过前需要先补填。</p>
            <label class="field">
              <span>评审意见（退回时必填）</span>
              <textarea v-model="review.comment" rows="3" placeholder="通过的结论，或退回的原因" />
            </label>
            <p v-if="error" class="err">{{ error }}</p>
          </div>
          <div class="dialog-foot">
            <button class="btn" @click="close">取消</button>
            <button class="btn danger" :disabled="busy" @click="submitReview(false)">退回</button>
            <button class="btn primary" :disabled="busy" @click="submitReview(true)">通过</button>
          </div>
        </template>

        <template v-else>
          <div class="panel-h">排入迭代</div>
          <div class="panel-b">
            <p class="small muted">{{ dialog.row.title }}</p>
            <label class="field">
              <span>目标迭代</span>
              <select v-model="schedule.sprintId">
                <option value="" disabled>选择迭代</option>
                <option v-for="s in store.sprints" :key="s.id" :value="String(s.id)">
                  {{ s.name }}（{{ s.status === 'ACTIVE' ? '进行中' : s.status === 'PLANNED' ? '规划中' : '已关闭' }}）
                </option>
              </select>
            </label>
            <p v-if="selectedSprint && selectedSprint.status === 'ACTIVE'" class="small">
              该迭代已开始，这条需求会按<strong>插队</strong>排入。插队只做提示，是否顺延其他需求由你决定。
            </p>
            <p v-else-if="selectedSprint" class="small muted">排入后可在管道排期里安排到人、到天。</p>
            <p v-if="error" class="err">{{ error }}</p>
          </div>
          <div class="dialog-foot">
            <button class="btn" @click="close">取消</button>
            <button class="btn primary" :disabled="busy || !schedule.sprintId" @click="submitSchedule">排入</button>
          </div>
        </template>
      </div>
    </div>
  </div>
</template>

<style scoped>
.handle { color: var(--ink-3); cursor: grab; margin-right: 2px; }
.handle.off { opacity: .3; cursor: not-allowed; }
.order-btns { display: inline-flex; gap: 2px; margin-left: 4px; }
.order-btns .link { font-size: 12px; }
.order-btns .link:disabled { color: var(--rule); cursor: not-allowed; text-decoration: none; }
tr[draggable="true"] { cursor: default; }
</style>
