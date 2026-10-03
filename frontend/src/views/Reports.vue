<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { get } from '../api'
import { isAdmin, isSupervisor, session } from '../session'
import { loadSprints, store } from '../store'
import BeatStrip from '../components/BeatStrip.vue'
import StatusChip from '../components/StatusChip.vue'
import { requirementStatus } from '../labels'

const route = useRoute()
const sprintId = ref(route.query.sprint ? Number(route.query.sprint) : null)
const report = ref(null)
const comparison = ref([])
const loading = ref(true)
const error = ref('')

const teamId = computed(() => (route.query.team ? Number(route.query.team) : session.user.teamId || null))
const crossTeam = computed(() => isSupervisor() || isAdmin())

const trendMarks = computed(() => {
  const marks = {}
  let previous = 0
  if (report.value) {
    report.value.dailyTrend.forEach((point) => {
      // 只在"当天有新完成"的日子画绿条，避免整轴铺满
      if (point.doneCount > previous) marks[point.date] = point.doneCount
      previous = point.doneCount
    })
  }
  return marks
})

const distribution = computed(() => {
  if (!report.value) return []
  const total = report.value.requirementCount || 1
  return report.value.statusDistribution.map((row) => ({
    ...row,
    width: Math.round((row.count / total) * 100),
  }))
})

async function load() {
  loading.value = true
  error.value = ''
  try {
    await loadSprints(teamId.value)
    if (!sprintId.value) {
      sprintId.value = store.current ? store.current.id : store.sprints[0] ? store.sprints[0].id : null
    }
    report.value = sprintId.value ? await get(`/reports/sprints/${sprintId.value}`) : null
    if (crossTeam.value) comparison.value = (await get('/reports/teams')) || []
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

onMounted(load)

function rate(value) {
  return value === null || value === undefined ? '—' : `${value}%`
}
</script>

<template>
  <div class="page">
    <div class="head">
      <h1>统计报表</h1>
      <div class="sub">迭代结束后的复盘依据，也是中途判断风险的依据</div>
      <div class="actions">
        <select v-model="sprintId" style="width: 200px" @change="load">
          <option :value="null" disabled>选择迭代</option>
          <option v-for="s in store.sprints" :key="s.id" :value="s.id">{{ s.name }}</option>
        </select>
      </div>
    </div>

    <p v-if="error" class="err">{{ error }}</p>
    <div v-if="loading" class="panel"><div class="empty">读取中</div></div>
    <div v-else-if="!report" class="panel"><div class="empty">还没有可统计的迭代。</div></div>

    <template v-else>
      <div class="panel">
        <div class="ledger">
          <div class="cell">
            <span class="k">完成率</span>
            <span class="v">{{ rate(report.completionRate) }}</span>
            <span class="small muted">{{ report.doneCount }} / {{ report.requirementCount }} 个需求</span>
          </div>
          <div class="cell">
            <span class="k">按期完成率</span>
            <span class="v">{{ rate(report.onTimeRate) }}</span>
            <span class="small muted">{{ report.onTimeCount }} 个在迭代截止前完成</span>
          </div>
          <div class="cell">
            <span class="k">插队需求</span>
            <span class="v">{{ report.urgentCount }}</span>
            <span class="small muted">占 {{ rate(report.urgentRate) }}</span>
          </div>
          <div class="cell">
            <span class="k">阻塞时长</span>
            <span class="v">{{ report.blockedDays }} 天</span>
            <span class="small muted">挂阻塞累计</span>
          </div>
        </div>
      </div>

      <div class="panel">
        <div class="panel-h">{{ report.sprint.name }} · {{ report.sprint.startDate }} 至 {{ report.sprint.endDate }}</div>
        <div class="panel-b">
          <BeatStrip :start="report.sprint.startDate" :end="report.sprint.endDate" :marks="trendMarks" />
          <p class="small muted trend-note">每格下沿的绿条是当天为止的累计完成需求数。</p>
        </div>
      </div>

      <div class="cols">
        <div class="panel">
          <div class="panel-h">状态分布</div>
          <div class="panel-b">
            <div v-if="!distribution.length" class="muted small">没有数据。</div>
            <div v-for="row in distribution" :key="row.status" class="dist">
              <StatusChip :map="requirementStatus" :value="row.status" />
              <div class="bar"><span :style="{ width: `${row.width}%` }" /></div>
              <span class="num small">{{ row.count }}</span>
            </div>
          </div>
        </div>

        <div class="panel">
          <div class="panel-h">人均负载</div>
          <div v-if="!report.memberLoad.length" class="empty">还没有分配到人的任务。</div>
          <table v-else class="table">
            <thead>
              <tr>
                <th>成员</th>
                <th class="num">任务数</th>
                <th class="num">涉及需求预估人日</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in report.memberLoad" :key="row.userId">
                <td>{{ row.name }}</td>
                <td class="num">{{ row.taskCount }}</td>
                <td class="num">{{ row.effortDays }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <template v-if="crossTeam">
        <div class="panel">
          <div class="panel-h">跨队完成率对比</div>
          <div class="panel-b tight">
            <table class="table">
              <thead>
                <tr>
                  <th>小队</th>
                  <th style="width: 150px">当前迭代</th>
                  <th class="num" style="width: 110px">需求 / 完成</th>
                  <th style="width: 190px">完成率</th>
                  <th class="num" style="width: 90px">延期风险</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="team in comparison" :key="team.teamId">
                  <td class="title">{{ team.teamName }}</td>
                  <td class="small">{{ team.sprintName || '—' }}</td>
                  <td class="num">{{ team.requirementCount }} / {{ team.doneCount }}</td>
                  <td>
                    <div class="bar"><span :style="{ width: `${team.completionRate}%` }" /></div>
                    <span class="small muted">{{ team.completionRate }}%</span>
                  </td>
                  <td class="num">
                    <span :class="{ risk: team.riskCount > 0 }">{{ team.riskCount }}</span>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <div class="panel">
          <div class="panel-h">各队延期风险需求</div>
          <div v-if="!comparison.some((t) => t.riskTop.length)" class="empty">各队都没有高风险需求。</div>
          <div v-else class="panel-b tight">
            <table class="table">
              <thead>
                <tr>
                  <th style="width: 150px">小队</th>
                  <th>需求</th>
                  <th style="width: 130px">状态</th>
                  <th class="num" style="width: 90px">预估人日</th>
                  <th style="width: 80px">阻塞</th>
                </tr>
              </thead>
              <tbody>
                <template v-for="team in comparison" :key="team.teamId">
                  <tr v-for="item in team.riskTop" :key="`${team.teamId}-${item.id}`">
                    <td class="small muted">{{ team.teamName }}</td>
                    <td>{{ item.title }}</td>
                    <td><StatusChip :map="requirementStatus" :value="item.status" /></td>
                    <td class="num">{{ item.estimate }}</td>
                    <td><span v-if="item.blocked" class="chip tone-risk">阻塞</span><span v-else class="muted small">—</span></td>
                  </tr>
                </template>
              </tbody>
            </table>
          </div>
        </div>
      </template>
    </template>
  </div>
</template>

<style scoped>
.ledger { display: flex; flex-wrap: wrap; }
.ledger .cell { flex: 1 1 170px; padding: 14px 18px; border-right: 1px solid var(--rule-soft); display: flex; flex-direction: column; gap: 4px; }
.ledger .cell:last-child { border-right: 0; }
.ledger .k { font-size: 12px; color: var(--ink-3); }
.ledger .v { font-size: 26px; font-weight: 680; letter-spacing: -.02em; }
.trend-note { margin-top: 10px; }
.cols { display: grid; grid-template-columns: repeat(auto-fit, minmax(320px, 1fr)); gap: 14px; }
.cols .panel + .panel { margin-top: 0; }
.dist { display: grid; grid-template-columns: 92px 1fr 30px; align-items: center; gap: 10px; margin-bottom: 8px; }
.risk { color: var(--risk); font-weight: 650; }
</style>
