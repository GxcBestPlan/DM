<script setup>
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { get } from '../api'
import StatusChip from '../components/StatusChip.vue'
import { requirementStatus, sprintStatus } from '../labels'

const teams = ref([])
const comparison = ref([])
const loading = ref(true)
const error = ref('')

const totalRequirements = computed(() => teams.value.reduce((sum, t) => sum + (t.requirementCount || 0), 0))
const totalRisk = computed(() => teams.value.reduce((sum, t) => sum + (t.riskCount || 0), 0))
const overloaded = computed(() => teams.value.filter((t) => t.overloaded).length)

const riskTop = computed(() => {
  const rows = []
  comparison.value.forEach((team) => {
    ;(team.riskTop || []).forEach((item) => rows.push({ ...item, teamName: team.teamName }))
  })
  return rows.sort((a, b) => Number(b.estimate) - Number(a.estimate)).slice(0, 6)
})

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [overview, compare] = await Promise.all([get('/overview'), get('/reports/teams')])
    teams.value = overview || []
    comparison.value = compare || []
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="head">
      <h1>跨队总览</h1>
      <div class="sub">只读视图。看各小队的迭代进度、负载与延期风险</div>
      <div class="actions">
        <RouterLink class="btn" to="/reports">看报表</RouterLink>
        <button class="btn" @click="load">刷新</button>
      </div>
    </div>

    <p v-if="error" class="err">{{ error }}</p>

    <div class="panel">
      <div class="ledger">
        <div class="cell"><span class="k">小队</span><span class="v">{{ teams.length }}</span></div>
        <div class="cell"><span class="k">在跑的需求</span><span class="v">{{ totalRequirements }}</span></div>
        <div class="cell"><span class="k">延期高风险</span><span class="v">{{ totalRisk }}</span></div>
        <div class="cell"><span class="k">负载超载的小队</span><span class="v">{{ overloaded }}</span></div>
      </div>
    </div>

    <div v-if="loading" class="panel"><div class="empty">读取中</div></div>
    <div v-else-if="!teams.length" class="panel"><div class="empty">还没有小队。管理员可以在组织管理里创建。</div></div>

    <div v-else class="panel">
      <div class="panel-h">各小队当前迭代</div>
      <div class="panel-b tight">
        <table class="table">
          <thead>
            <tr>
              <th>小队</th>
              <th style="width: 150px">当前迭代</th>
              <th style="width: 80px">状态</th>
              <th class="num" style="width: 70px">剩余</th>
              <th class="num" style="width: 110px">需求 / 完成</th>
              <th class="num" style="width: 90px">阻塞</th>
              <th class="num" style="width: 110px">延期风险</th>
              <th style="width: 150px">人力负载</th>
              <th style="width: 170px">下钻</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="team in teams" :key="team.teamId">
              <td>
                <div class="title">{{ team.teamName }}</div>
                <div class="small muted">{{ team.memberCount }} 人 · 开发 {{ team.devCount }} 人</div>
              </td>
              <td class="small">{{ team.sprintName || '无进行中迭代' }}</td>
              <td><StatusChip v-if="team.sprintStatus" :map="sprintStatus" :value="team.sprintStatus" /><span v-else class="muted">—</span></td>
              <td class="num">{{ team.sprintId ? `${team.daysLeft} 天` : '—' }}</td>
              <td class="num">{{ team.requirementCount }} / {{ team.doneCount }}</td>
              <td class="num">{{ team.blockedCount }}</td>
              <td class="num">
                <span :class="{ risk: team.riskCount > 0 }">{{ team.riskCount }}</span>
              </td>
              <td>
                <div class="bar" :class="{ over: team.overloaded }">
                  <span :style="{ width: `${Math.min(team.loadPercent, 100)}%` }" />
                </div>
                <div class="small" :class="{ risk: team.overloaded }">
                  {{ team.loadPercent }}%{{ team.overloaded ? ' 超载' : '' }}
                </div>
              </td>
              <td class="nowrap">
                <RouterLink v-if="team.sprintId" class="btn sm" :to="`/board?team=${team.teamId}&sprint=${team.sprintId}`">看板</RouterLink>
                <RouterLink v-if="team.sprintId" class="btn sm" :to="`/pipeline?team=${team.teamId}&sprint=${team.sprintId}`">管道</RouterLink>
                <RouterLink class="btn sm" :to="`/reports?team=${team.teamId}`">报表</RouterLink>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <div v-if="riskTop.length" class="panel">
      <div class="panel-h">延期风险最大的需求</div>
      <div class="panel-b tight">
        <table class="table">
          <thead>
            <tr>
              <th>需求</th>
              <th style="width: 160px">小队</th>
              <th style="width: 130px">状态</th>
              <th class="num" style="width: 90px">预估人日</th>
              <th style="width: 90px">阻塞</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in riskTop" :key="`${item.teamName}-${item.id}`">
              <td><RouterLink class="title" :to="`/r/${item.id}`">{{ item.title }}</RouterLink></td>
              <td class="small muted">{{ item.teamName }}</td>
              <td><StatusChip :map="requirementStatus" :value="item.status" /></td>
              <td class="num">{{ item.estimate }}</td>
              <td>
                <span v-if="item.blocked" class="chip tone-risk">阻塞</span>
                <span v-else class="muted small">—</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  </div>
</template>

<style scoped>
.ledger { display: flex; flex-wrap: wrap; }
.ledger .cell { flex: 1 1 150px; padding: 12px 16px; border-right: 1px solid var(--rule-soft); display: flex; flex-direction: column; gap: 3px; }
.ledger .cell:last-child { border-right: 0; }
.ledger .k { font-size: 12px; color: var(--ink-3); }
.ledger .v { font-size: 20px; font-weight: 680; letter-spacing: -.02em; }
.risk { color: var(--risk); font-weight: 650; }
.bar { width: 100%; margin-bottom: 4px; }
</style>
