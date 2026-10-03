<script setup>
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { get, post } from '../api'
import StatusChip from '../components/StatusChip.vue'
import { loadSprints, store } from '../store'
import { requirementStatus, requirementType } from '../labels'

const queue = ref([])
const testing = ref([])
const loading = ref(true)
const error = ref('')
const busy = ref(false)
const reject = ref({ open: false, row: null, comment: '' })

const queueCount = computed(() => queue.value.length)

function sprintName(id) {
  if (!id) return '—'
  const hit = store.sprints.find((s) => s.id === id)
  return hit ? hit.name : `#${id}`
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    await loadSprints()
    const [q, t] = await Promise.all([
      get('/requirements?all=true&status=READY_FOR_TEST'),
      get('/requirements?all=true&status=TESTING'),
    ])
    queue.value = q || []
    testing.value = t || []
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

onMounted(load)

async function claim(row) {
  busy.value = true
  error.value = ''
  try {
    await post(`/requirements/${row.id}/claim`)
    await load()
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = false
  }
}

async function accept(row) {
  busy.value = true
  error.value = ''
  try {
    await post(`/requirements/${row.id}/accept`)
    await load()
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = false
  }
}

function openReject(row) {
  reject.value = { open: true, row, comment: '' }
}

async function submitReject() {
  busy.value = true
  error.value = ''
  try {
    await post(`/requirements/${reject.value.row.id}/reject`, { comment: reject.value.comment })
    reject.value = { open: false, row: null, comment: '' }
    await load()
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="page">
    <div class="head">
      <h1>测试工作台</h1>
      <div class="sub">
        <span>待测试 {{ queueCount }} 条</span>
        <span>测试中 {{ testing.length }} 条</span>
      </div>
      <div class="actions">
        <button class="btn" @click="load">刷新</button>
      </div>
    </div>

    <p v-if="error" class="err">{{ error }}</p>

    <div class="panel">
      <div class="panel-h">待测试队列</div>
      <div v-if="loading" class="empty">读取中</div>
      <div v-else-if="!queue.length" class="empty">队列是空的，暂时没有等待验收的需求。</div>
      <div v-else class="panel-b tight">
        <table class="table">
          <thead>
            <tr>
              <th>需求</th>
              <th style="width: 96px">类型</th>
              <th style="width: 120px">所属迭代</th>
              <th style="width: 100px">预估人日</th>
              <th style="width: 100px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in queue" :key="row.id">
              <td>
                <RouterLink class="title" :to="`/r/${row.id}`">{{ row.title }}</RouterLink>
                <div v-if="row.acceptanceCriteria" class="small muted">验收标准：{{ row.acceptanceCriteria }}</div>
              </td>
              <td class="small">{{ requirementType[row.type] }}</td>
              <td class="small muted">{{ sprintName(row.sprintId) }}</td>
              <td class="num">{{ row.estimate }}</td>
              <td><button class="btn sm primary" :disabled="busy" @click="claim(row)">领取</button></td>
            </tr>
          </tbody>
        </table>
        <p class="small muted pad">待测试堆积时，队列对开发管理者同样可见。</p>
      </div>
    </div>

    <div class="panel">
      <div class="panel-h">测试中</div>
      <div v-if="!testing.length" class="empty">还没有领取的需求。</div>
      <div v-else class="panel-b tight">
        <table class="table">
          <thead>
            <tr>
              <th>需求</th>
              <th>验收标准</th>
              <th style="width: 200px">结论</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in testing" :key="row.id">
              <td>
                <RouterLink class="title" :to="`/r/${row.id}`">{{ row.title }}</RouterLink>
                <div class="small muted">
                  <StatusChip :map="requirementStatus" :value="row.status" />
                </div>
              </td>
              <td class="small">{{ row.acceptanceCriteria || '—' }}</td>
              <td class="nowrap">
                <button class="btn sm primary" :disabled="busy" @click="accept(row)">通过</button>
                <button class="btn sm danger" :disabled="busy" @click="openReject(row)">退回</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <div v-if="reject.open" class="scrim" @click.self="reject.open = false">
      <div class="dialog">
        <div class="panel-h">退回需求</div>
        <div class="panel-b">
          <p class="small muted">{{ reject.row.title }}</p>
          <label class="field">
            <span>退回原因（必填）</span>
            <textarea v-model="reject.comment" rows="3" placeholder="如：弱网下首屏 2.3 秒，未达验收标准" />
          </label>
          <p class="small muted">退回后需求回到「开发中」，开发任务需要按需重新指派。</p>
        </div>
        <div class="dialog-foot">
          <button class="btn" @click="reject.open = false">取消</button>
          <button class="btn danger" :disabled="busy" @click="submitReject">确认退回</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.pad { padding: 10px 12px 2px; }
.head .sub { display: flex; gap: 14px; }
</style>
