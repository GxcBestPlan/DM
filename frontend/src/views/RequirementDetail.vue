<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { del, get, post, put } from '../api'
import { hasRole, isAdmin } from '../session'
import { loadSprints, store } from '../store'
import StatusChip from '../components/StatusChip.vue'
import TaskActions from '../components/TaskActions.vue'
import { globalRole, requirementStatus, requirementType, taskStatus, taskType } from '../labels'

const route = useRoute()
const id = Number(route.params.id)

const detail = ref(null)
const members = ref([])
const loading = ref(true)
const error = ref('')
const busy = ref(false)

const taskDialog = reactive({ open: false, editing: null })
const taskForm = reactive({ type: 'BACKEND', title: '', assigneeId: '', plannedStartDate: '', plannedEndDate: '' })
const depDialog = reactive({ open: false, task: null, dependsOnTaskId: '' })
const blockDialog = reactive({ open: false, reason: '' })
const rejectDialog = reactive({ open: false, comment: '' })
const reviewDialog = reactive({ open: false, comment: '' })
const scheduleDialog = reactive({ open: false, sprintId: '' })

const status = computed(() => (detail.value ? detail.value.status : ''))
const tasks = computed(() => (detail.value && detail.value.tasks) || [])
const backendTasks = computed(() => tasks.value.filter((task) => task.type === 'BACKEND'))
const sprint = computed(
  () => (detail.value && store.sprints.find((s) => s.id === detail.value.sprintId)) || null,
)
const sprintLabel = computed(() => {
  if (!detail.value || !detail.value.sprintId) return '需求池'
  return sprint.value ? `${sprint.value.name}（${sprint.value.status === 'ACTIVE' ? '进行中' : sprint.value.status === 'PLANNED' ? '规划中' : '已关闭'}）` : `#${detail.value.sprintId}`
})

const canManage = computed(() => isAdmin() || hasRole('DEV_MANAGER'))
const canPm = computed(() => isAdmin() || hasRole('PM'))
const canTest = computed(() => isAdmin() || hasRole('TESTER'))
const readOnly = computed(() => isAdmin() === false && !canManage.value && !canPm.value && !canTest.value)

const nameOf = computed(() => {
  const map = new Map()
  members.value.forEach((m) => map.set(m.userId, m.name))
  if (detail.value) map.set(detail.value.createdBy, detail.value.createdByName)
  return map
})

const plannedSprints = computed(() => store.sprints.filter((s) => s.status === 'PLANNED'))

async function load() {
  loading.value = true
  error.value = ''
  try {
    detail.value = await get(`/requirements/${id}`)
    await loadSprints(detail.value.teamId)
    try {
      members.value = (await get(`/org/teams/${detail.value.teamId}/members`)) || []
    } catch {
      members.value = []
    }
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

onMounted(load)

async function act(path, label, body) {
  busy.value = true
  error.value = ''
  try {
    await post(path, body)
    await load()
  } catch (e) {
    error.value = `${label}：${e.message}`
  } finally {
    busy.value = false
  }
}

function openTaskDialog(row) {
  taskDialog.open = true
  taskDialog.editing = row || null
  Object.assign(taskForm, {
    type: row ? row.type : 'BACKEND',
    title: row ? row.title : '',
    assigneeId: row && row.assigneeId ? String(row.assigneeId) : '',
    plannedStartDate: row && row.plannedStartDate ? row.plannedStartDate : '',
    plannedEndDate: row && row.plannedEndDate ? row.plannedEndDate : '',
  })
}

async function saveTask() {
  busy.value = true
  error.value = ''
  const body = {
    type: taskForm.type,
    title: taskForm.title,
    assigneeId: taskForm.assigneeId ? Number(taskForm.assigneeId) : null,
    plannedStartDate: taskForm.plannedStartDate || null,
    plannedEndDate: taskForm.plannedEndDate || null,
  }
  try {
    if (taskDialog.editing) await put(`/tasks/${taskDialog.editing.id}`, body)
    else await post(`/requirements/${id}/tasks`, body)
    taskDialog.open = false
    await load()
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = false
  }
}

async function removeTask(row) {
  if (!window.confirm(`删除任务「${row.title}」？相关依赖会一并删除。`)) return
  try {
    await del(`/tasks/${row.id}`)
    await load()
  } catch (e) {
    error.value = e.message
  }
}

function openDependency(task) {
  depDialog.open = true
  depDialog.task = task
  depDialog.dependsOnTaskId = ''
}

async function addDependency() {
  busy.value = true
  error.value = ''
  try {
    await post(`/tasks/${depDialog.task.id}/dependencies`, {
      dependsOnTaskId: Number(depDialog.dependsOnTaskId),
    })
    depDialog.open = false
    await load()
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = false
  }
}

async function removeDependency(task, dependsOnTaskId) {
  try {
    await del(`/tasks/${task.id}/dependencies/${dependsOnTaskId}`)
    await load()
  } catch (e) {
    error.value = e.message
  }
}

async function submitReview(approved) {
  await act(`/requirements/${id}/review`, '登记评审', { approved, comment: reviewDialog.comment }, () => {
    reviewDialog.open = false
  })
  reviewDialog.open = false
}

async function submitSchedule() {
  const target = store.sprints.find((s) => String(s.id) === String(scheduleDialog.sprintId))
  await act(
    `/requirements/${id}/schedule`,
    '排入迭代',
    { sprintId: Number(scheduleDialog.sprintId), urgent: !!(target && target.status === 'ACTIVE') },
  )
  scheduleDialog.open = false
}

async function submitBlock() {
  await act(`/requirements/${id}/block`, '挂阻塞', { reason: blockDialog.reason })
  blockDialog.open = false
}

async function submitReject() {
  await act(`/requirements/${id}/reject`, '退回需求', { comment: rejectDialog.comment })
  rejectDialog.open = false
}

async function cancelRequirement() {
  if (!window.confirm('取消这条需求？取消后不再出现在列表里，但记录保留用于统计。')) return
  await act(`/requirements/${id}/cancel`, '取消需求')
}

function taskTitle(taskId) {
  const hit = tasks.value.find((t) => t.id === taskId)
  return hit ? hit.title : `#${taskId}`
}

function operatorName(operatorId) {
  if (!operatorId) return '系统'
  return nameOf.value.get(operatorId) || `#${operatorId}`
}
</script>

<template>
  <div class="page">
    <div class="head">
      <h1 v-if="detail">{{ detail.title }}</h1>
      <h1 v-else>需求详情</h1>
      <div class="sub">
        <RouterLink class="link" to="/board">返回看板</RouterLink>
        <span v-if="detail">{{ sprintLabel }}</span>
      </div>
      <div class="actions" v-if="detail">
        <StatusChip :map="requirementStatus" :value="detail.status" />
        <span v-if="detail.urgent" class="chip tone-risk">插队</span>
        <span v-if="detail.blocked" class="chip tone-risk">阻塞</span>
      </div>
    </div>

    <p v-if="error" class="err">{{ error }}</p>
    <div v-if="loading" class="panel"><div class="empty">读取中</div></div>

    <template v-else-if="detail">
      <div v-if="detail.blocked" class="panel block-note">
        <div class="panel-b">
          <strong>当前处于阻塞</strong>
          <span class="small">{{ detail.blockedReason }}</span>
          <button v-if="canManage" class="btn sm" :disabled="busy" @click="act(`/requirements/${id}/unblock`, '解除阻塞')">
            解除阻塞
          </button>
        </div>
      </div>

      <div v-if="canManage || canPm || canTest" class="panel">
        <div class="panel-h">可以做的事</div>
        <div class="panel-b row">
          <button
            v-if="canPm && status === 'PENDING_REVIEW'"
            class="btn primary"
            :disabled="busy"
            @click="reviewDialog.open = true"
          >
            登记评审
          </button>
          <button
            v-if="canManage && status === 'REVIEWED'"
            class="btn primary"
            :disabled="busy"
            @click="scheduleDialog.open = true; scheduleDialog.sprintId = plannedSprints.length ? String(plannedSprints[0].id) : ''"
          >
            排入迭代
          </button>
          <button v-if="canManage && status === 'PLANNED' && sprint && sprint.status === 'PLANNED'" class="btn" :disabled="busy" @click="act(`/requirements/${id}/unschedule`, '移出迭代')">
            移出迭代
          </button>
          <button v-if="canManage && status === 'PLANNED'" class="btn primary" :disabled="busy" @click="act(`/requirements/${id}/dev`, '开始开发')">
            开始开发
          </button>
          <button v-if="canManage && status === 'DEV'" class="btn primary" :disabled="busy" @click="act(`/requirements/${id}/ready-for-test`, '转为待测试')">
            转为待测试
          </button>
          <button v-if="canTest && status === 'READY_FOR_TEST'" class="btn primary" :disabled="busy" @click="act(`/requirements/${id}/claim`, '领取')">
            领取测试
          </button>
          <button v-if="canTest && status === 'TESTING'" class="btn primary" :disabled="busy" @click="act(`/requirements/${id}/accept`, '测试通过')">
            测试通过
          </button>
          <button v-if="canTest && status === 'TESTING'" class="btn danger" :disabled="busy" @click="rejectDialog.open = true">
            退回
          </button>
          <button v-if="canManage && status === 'ACCEPTED'" class="btn primary" :disabled="busy" @click="act(`/requirements/${id}/publish`, '标记已发布')">
            标记已发布
          </button>
          <button v-if="canManage && !detail.blocked" class="btn" :disabled="busy" @click="blockDialog.open = true">
            挂阻塞
          </button>
          <button class="btn danger" :disabled="busy" @click="cancelRequirement">取消需求</button>
          <span v-if="readOnly" class="muted small">当前角色对该需求只有查看权限。</span>
        </div>
      </div>

      <div class="panel">
        <div class="panel-h">需求内容</div>
        <div class="panel-b">
          <dl class="kv">
            <dt>类型</dt><dd>{{ requirementType[detail.type] }}</dd>
            <dt>预估人日</dt><dd>{{ detail.estimate }}</dd>
            <dt>创建</dt><dd>{{ detail.createdByName }}，{{ (detail.createdAt || '').replace('T', ' ').slice(0, 16) }}</dd>
            <dt>描述</dt><dd>{{ detail.description || '—' }}</dd>
            <dt>验收标准</dt><dd>{{ detail.acceptanceCriteria || '未填写（评审通过前必填）' }}</dd>
            <dt>外部依赖</dt><dd>{{ detail.externalNote || '—' }}</dd>
          </dl>
        </div>
      </div>

      <div class="panel">
        <div class="panel-h">
          任务与依赖
          <div class="actions">
            <button v-if="canManage" class="btn sm primary" @click="openTaskDialog(null)">拆任务</button>
          </div>
        </div>
        <div v-if="!tasks.length" class="empty">还没有拆任务。前后端各拆一条，测试在需求级进行。</div>
        <div v-else class="panel-b tight">
          <table class="table">
            <thead>
              <tr>
                <th style="width: 66px">类型</th>
                <th>任务</th>
                <th style="width: 100px">负责人</th>
                <th style="width: 96px">状态</th>
                <th style="width: 150px">计划日期</th>
                <th>依赖</th>
                <th style="width: 230px">更新</th>
                <th v-if="canManage" style="width: 150px">管理</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="task in tasks" :key="task.id">
                <td class="small">{{ taskType[task.type] }}</td>
                <td>
                  {{ task.title }}
                  <div v-if="task.progressNote" class="small muted">{{ task.progressNote }}</div>
                </td>
                <td class="small">{{ task.assigneeName || '未分配' }}</td>
                <td>
                  <StatusChip :map="taskStatus" :value="task.status" />
                  <span v-if="task.blocked" class="chip tone-risk ml">阻塞</span>
                </td>
                <td class="small muted nowrap">
                  {{ task.plannedStartDate || '未排' }}
                  <template v-if="task.plannedEndDate"> 至 {{ task.plannedEndDate }}</template>
                </td>
                <td class="small">
                  <template v-if="task.dependsOnTaskIds && task.dependsOnTaskIds.length">
                    <span v-for="depId in task.dependsOnTaskIds" :key="depId" class="dep">
                      {{ taskTitle(depId) }}
                      <button v-if="canManage" class="link" @click="removeDependency(task, depId)">移除</button>
                    </span>
                  </template>
                  <template v-else>
                    <span class="muted">—</span>
                    <button v-if="canManage && task.type === 'FRONTEND' && backendTasks.length" class="link ml" @click="openDependency(task)">
                      加依赖
                    </button>
                  </template>
                </td>
                <td><TaskActions :task="task" @changed="load" /></td>
                <td v-if="canManage" class="nowrap">
                  <button class="btn sm" @click="openTaskDialog(task)">编辑</button>
                  <button class="btn sm danger" @click="removeTask(task)">删除</button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <div class="panel">
        <div class="panel-h">状态历史</div>
        <div class="panel-b">
          <ul class="timeline">
            <li v-for="(log, index) in detail.history" :key="index">
              <div class="when">
                {{ (log.createdAt || '').replace('T', ' ').slice(0, 16) }} · {{ operatorName(log.operatorId) }}
              </div>
              <div>
                {{ log.fromStatus ? requirementStatus[log.fromStatus].label + ' → ' : '' }}
                {{ requirementStatus[log.toStatus] ? requirementStatus[log.toStatus].label : log.toStatus }}
                <span v-if="log.comment" class="muted">（{{ log.comment }}）</span>
              </div>
            </li>
          </ul>
        </div>
      </div>
    </template>

    <!-- 拆任务 / 编辑任务 -->
    <div v-if="taskDialog.open" class="scrim" @click.self="taskDialog.open = false">
      <div class="dialog">
        <div class="panel-h">{{ taskDialog.editing ? '编辑任务' : '拆任务' }}</div>
        <div class="panel-b">
          <div class="row">
            <label class="field" style="flex: 1">
              <span>类型</span>
              <select v-model="taskForm.type">
                <option value="FRONTEND">前端</option>
                <option value="BACKEND">后端</option>
              </select>
            </label>
            <label class="field" style="flex: 2">
              <span>任务标题</span>
              <input v-model="taskForm.title" placeholder="如：导出接口（异步任务）" />
            </label>
          </div>
          <label class="field">
            <span>负责人</span>
            <select v-model="taskForm.assigneeId">
              <option value="">暂不分配</option>
              <option v-for="m in members" :key="m.userId" :value="String(m.userId)">
                {{ m.name }}（{{ (m.roles || []).map((r) => globalRole[r] || r).join('、') }}）
              </option>
            </select>
          </label>
          <div class="row">
            <label class="field" style="flex: 1">
              <span>计划开始</span>
              <input v-model="taskForm.plannedStartDate" type="date" />
            </label>
            <label class="field" style="flex: 1">
              <span>计划结束</span>
              <input v-model="taskForm.plannedEndDate" type="date" />
            </label>
          </div>
          <p class="small muted">计划日期需要落在迭代周期内。</p>
        </div>
        <div class="dialog-foot">
          <button class="btn" @click="taskDialog.open = false">取消</button>
          <button class="btn primary" :disabled="busy" @click="saveTask">保存</button>
        </div>
      </div>
    </div>

    <!-- 加依赖 -->
    <div v-if="depDialog.open" class="scrim" @click.self="depDialog.open = false">
      <div class="dialog">
        <div class="panel-h">加依赖</div>
        <div class="panel-b">
          <p class="small muted">{{ depDialog.task.title }} 依赖哪条后端任务？</p>
          <label class="field">
            <span>被依赖的后端任务</span>
            <select v-model="depDialog.dependsOnTaskId">
              <option value="" disabled>选择任务</option>
              <option v-for="t in backendTasks" :key="t.id" :value="String(t.id)">{{ t.title }}</option>
            </select>
          </label>
          <p class="small muted">依赖只做顺序提示，不会阻断状态流转。</p>
        </div>
        <div class="dialog-foot">
          <button class="btn" @click="depDialog.open = false">取消</button>
          <button class="btn primary" :disabled="busy || !depDialog.dependsOnTaskId" @click="addDependency">添加</button>
        </div>
      </div>
    </div>

    <!-- 挂阻塞 -->
    <div v-if="blockDialog.open" class="scrim" @click.self="blockDialog.open = false">
      <div class="dialog">
        <div class="panel-h">挂阻塞</div>
        <div class="panel-b">
          <label class="field">
            <span>阻塞原因（必填）</span>
            <textarea v-model="blockDialog.reason" rows="3" placeholder="如：等待基础平台队提供导出组件 v2" />
          </label>
        </div>
        <div class="dialog-foot">
          <button class="btn" @click="blockDialog.open = false">取消</button>
          <button class="btn primary" :disabled="busy" @click="submitBlock">确认</button>
        </div>
      </div>
    </div>

    <!-- 评审登记 -->
    <div v-if="reviewDialog.open" class="scrim" @click.self="reviewDialog.open = false">
      <div class="dialog">
        <div class="panel-h">登记评审结果</div>
        <div class="panel-b">
          <p v-if="!detail.acceptanceCriteria" class="err">还没有验收标准，通过前需要先补填。</p>
          <label class="field">
            <span>评审意见（退回时必填）</span>
            <textarea v-model="reviewDialog.comment" rows="3" placeholder="通过的结论，或退回的原因" />
          </label>
        </div>
        <div class="dialog-foot">
          <button class="btn" @click="reviewDialog.open = false">取消</button>
          <button class="btn danger" :disabled="busy" @click="submitReview(false)">退回</button>
          <button class="btn primary" :disabled="busy" @click="submitReview(true)">通过</button>
        </div>
      </div>
    </div>

    <!-- 排入迭代 -->
    <div v-if="scheduleDialog.open" class="scrim" @click.self="scheduleDialog.open = false">
      <div class="dialog">
        <div class="panel-h">排入迭代</div>
        <div class="panel-b">
          <label class="field">
            <span>目标迭代</span>
            <select v-model="scheduleDialog.sprintId">
              <option value="" disabled>选择迭代</option>
              <option v-for="s in store.sprints" :key="s.id" :value="String(s.id)">
                {{ s.name }}（{{ s.status === 'ACTIVE' ? '进行中，将按插队排入' : s.status === 'PLANNED' ? '规划中' : '已关闭' }}）
              </option>
            </select>
          </label>
        </div>
        <div class="dialog-foot">
          <button class="btn" @click="scheduleDialog.open = false">取消</button>
          <button class="btn primary" :disabled="busy || !scheduleDialog.sprintId" @click="submitSchedule">排入</button>
        </div>
      </div>
    </div>

    <!-- 退回 -->
    <div v-if="rejectDialog.open" class="scrim" @click.self="rejectDialog.open = false">
      <div class="dialog">
        <div class="panel-h">退回需求</div>
        <div class="panel-b">
          <label class="field">
            <span>退回原因（必填）</span>
            <textarea v-model="rejectDialog.comment" rows="3" placeholder="如：弱网下首屏 2.3 秒，未达验收标准" />
          </label>
          <p class="small muted">退回后需求回到「开发中」，开发任务需要按需重新指派。</p>
        </div>
        <div class="dialog-foot">
          <button class="btn" @click="rejectDialog.open = false">取消</button>
          <button class="btn danger" :disabled="busy" @click="submitReject">确认退回</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.block-note .panel-b { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.block-note strong { color: var(--risk); }
.head .sub { display: flex; gap: 14px; align-items: center; }
.ml { margin-left: 6px; }
.dep { display: inline-flex; align-items: center; gap: 6px; margin-right: 8px; white-space: nowrap; }
</style>
