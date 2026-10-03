<script setup>
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { get } from '../api'
import StatusChip from '../components/StatusChip.vue'
import TaskActions from '../components/TaskActions.vue'
import { taskStatus } from '../labels'

const scope = ref('today')
const rows = ref([])
const loading = ref(true)
const error = ref('')

async function load() {
  loading.value = true
  error.value = ''
  try {
    rows.value = (await get(`/tasks/mine?scope=${scope.value}`)) || []
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

onMounted(load)

const grouped = computed(() => {
  const map = new Map()
  rows.value.forEach((row) => {
    const key = row.sprintName || '未排入迭代'
    if (!map.has(key)) map.set(key, [])
    map.get(key).push(row)
  })
  return [...map.entries()]
})
</script>

<template>
  <div class="page">
    <div class="head">
      <h1>我的任务</h1>
      <div class="sub">{{ scope === 'today' ? '进行中的，和今天该开始的' : '所有迭代里指派给我的任务' }}</div>
      <div class="actions">
        <button class="btn" :class="{ primary: scope === 'today' }" @click="scope = 'today'; load()">今日</button>
        <button class="btn" :class="{ primary: scope === 'all' }" @click="scope = 'all'; load()">全部</button>
      </div>
    </div>

    <p v-if="error" class="err">{{ error }}</p>
    <div v-if="loading" class="panel"><div class="empty">读取中</div></div>
    <div v-else-if="!rows.length" class="panel">
      <div class="empty">
        {{ scope === 'today' ? '今天没有指派给你的任务。' : '还没有任务指派给你。' }}
        需要调整分工，找开发管理者在管道排期里改。
      </div>
    </div>

    <template v-else>
      <div v-for="[sprintName, tasks] in grouped" :key="sprintName" class="panel">
        <div class="panel-h">{{ sprintName }}</div>
        <div class="panel-b tight">
          <table class="table">
            <thead>
              <tr>
                <th>需求</th>
                <th>任务</th>
                <th style="width: 96px">状态</th>
                <th style="width: 150px">计划日期</th>
                <th>进展</th>
                <th style="width: 250px">更新</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="task in tasks" :key="task.id">
                <td>
                  <RouterLink class="title" :to="`/r/${task.requirementId}`">{{ task.requirementTitle }}</RouterLink>
                </td>
                <td class="small">{{ task.title }}</td>
                <td>
                  <StatusChip :map="taskStatus" :value="task.status" />
                  <span v-if="task.blocked" class="chip tone-risk ml">阻塞</span>
                </td>
                <td class="small muted nowrap">
                  {{ task.plannedStartDate || '未排' }}
                  <template v-if="task.plannedEndDate"> 至 {{ task.plannedEndDate }}</template>
                </td>
                <td class="small">{{ task.progressNote || '—' }}</td>
                <td><TaskActions :task="task" @changed="load" /></td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.ml { margin-left: 6px; }
</style>
