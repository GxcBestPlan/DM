<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { del, get, post, put } from '../api'
import { globalRole, memberRole } from '../labels'

const ROLES = ['PM', 'DEV_MANAGER', 'FRONTEND_DEV', 'BACKEND_DEV', 'TESTER']

const teams = ref([])
const users = ref([])
const members = ref([])
const teamId = ref(null)
const loading = ref(true)
const error = ref('')
const notice = ref('')
const busy = ref(false)

const teamForm = reactive({ name: '', description: '' })
const userForm = reactive({ account: '', name: '', password: '', globalRole: 'USER' })
const memberForm = reactive({ userId: '', roles: ['FRONTEND_DEV'] })

const roleDraft = reactive({})

const teamUserIds = computed(() => new Set(members.value.map((m) => m.userId)))
const addable = computed(() => users.value.filter((u) => !teamUserIds.value.has(u.id)))

async function loadTeams() {
  teams.value = (await get('/org/teams')) || []
  if (!teamId.value && teams.value.length) teamId.value = teams.value[0].id
}

async function loadUsers() {
  users.value = (await get('/org/users')) || []
}

async function loadMembers() {
  if (!teamId.value) {
    members.value = []
    return
  }
  members.value = (await get(`/org/teams/${teamId.value}/members`)) || []
  members.value.forEach((m) => {
    roleDraft[m.userId] = [...(m.roles || [])]
  })
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    await Promise.all([loadTeams(), loadUsers()])
    await loadMembers()
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

onMounted(load)

async function guard(action, success) {
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    await action()
    notice.value = success
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = false
  }
}

const createTeam = () =>
  guard(async () => {
    await post('/org/teams', { ...teamForm })
    teamForm.name = ''
    teamForm.description = ''
    await loadTeams()
  }, '小队已创建')

const renameTeam = (team) => {
  const name = window.prompt('小队名称', team.name)
  if (!name) return
  guard(async () => {
    await put(`/org/teams/${team.id}`, { name, description: team.description })
    await loadTeams()
  }, '小队已更新')
}

const removeTeam = (team) => {
  if (!window.confirm(`删除小队「${team.name}」？队内还有成员或需求时系统会拒绝。`)) return
  guard(async () => {
    await del(`/org/teams/${team.id}`)
    if (teamId.value === team.id) teamId.value = null
    await loadTeams()
    await loadMembers()
  }, '小队已删除')
}

const createUser = () =>
  guard(async () => {
    await post('/org/users', { ...userForm })
    userForm.account = ''
    userForm.name = ''
    userForm.password = ''
    await loadUsers()
  }, '账号已创建')

const toggleUser = (user) =>
  guard(async () => {
    await put(`/org/users/${user.id}/status`, { enabled: !user.enabled })
    await loadUsers()
    await loadMembers()
  }, '账号状态已更新')

const resetPassword = (user) => {
  const password = window.prompt(`为 ${user.name} 设置新密码（至少 6 位）`)
  if (!password) return
  guard(async () => {
    await post(`/org/users/${user.id}/password`, { newPassword: password })
  }, '密码已重置')
}

const addMember = () =>
  guard(async () => {
    await post(`/org/teams/${teamId.value}/members`, {
      userId: Number(memberForm.userId),
      roles: memberForm.roles,
    })
    memberForm.userId = ''
    await loadTeams()
    await loadMembers()
  }, '成员已加入小队')

const saveRoles = (member) =>
  guard(async () => {
    await put(`/org/teams/${teamId.value}/members/${member.userId}`, { roles: roleDraft[member.userId] })
    await loadMembers()
  }, '角色已更新')

const removeMember = (member) => {
  if (!window.confirm(`把 ${member.name} 移出本小队？该账号会变成无小队状态。`)) return
  guard(async () => {
    await del(`/org/teams/${teamId.value}/members/${member.userId}`)
    await loadTeams()
    await loadMembers()
  }, '成员已移出')
}

function toggleRole(list, role) {
  const index = list.indexOf(role)
  if (index >= 0) list.splice(index, 1)
  else list.push(role)
}

async function switchTeam() {
  try {
    await loadMembers()
  } catch (e) {
    error.value = e.message
  }
}
</script>

<template>
  <div class="page">
    <div class="head">
      <h1>组织管理</h1>
      <div class="sub">小队、成员与角色、账号。一个用户只属于一个小队，可在队内担任多个角色</div>
      <div class="actions">
        <button class="btn" @click="load">刷新</button>
      </div>
    </div>

    <p v-if="error" class="err">{{ error }}</p>
    <p v-if="notice" class="ok">{{ notice }}</p>

    <div class="panel">
      <div class="panel-h">小队</div>
      <div v-if="loading" class="empty">读取中</div>
      <div v-else-if="!teams.length" class="empty">还没有小队，先创建一个小队再往里加人。</div>
      <div v-else class="panel-b tight">
        <table class="table">
          <thead>
            <tr>
              <th>名称</th>
              <th>描述</th>
              <th class="num" style="width: 90px">成员数</th>
              <th style="width: 170px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="team in teams" :key="team.id">
              <td class="title">{{ team.name }}</td>
              <td class="small muted">{{ team.description || '—' }}</td>
              <td class="num">{{ team.memberCount }}</td>
              <td class="nowrap">
                <button class="btn sm" @click="renameTeam(team)">改名</button>
                <button class="btn sm danger" @click="removeTeam(team)">删除</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="panel-b row">
        <input v-model="teamForm.name" placeholder="小队名称" style="width: 180px" />
        <input v-model="teamForm.description" placeholder="描述（选填）" style="flex: 1" />
        <button class="btn primary" :disabled="busy || !teamForm.name" @click="createTeam">新建小队</button>
      </div>
    </div>

    <div class="panel">
      <div class="panel-h">
        成员与角色
        <div class="actions">
          <select v-model="teamId" style="width: 180px" @change="switchTeam">
            <option v-for="team in teams" :key="team.id" :value="team.id">{{ team.name }}</option>
          </select>
        </div>
      </div>
      <div v-if="!teamId" class="empty">先选择一个小队。</div>
      <div v-else-if="!members.length" class="empty">这个小队还没有成员。在下面把账号加进来。</div>
      <div v-else class="panel-b tight">
        <table class="table">
          <thead>
            <tr>
              <th>姓名</th>
              <th style="width: 140px">账号</th>
              <th>队内角色（可多选）</th>
              <th style="width: 90px">账号</th>
              <th style="width: 160px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="member in members" :key="member.userId">
              <td class="title">{{ member.name }}</td>
              <td class="small muted">{{ member.account }}</td>
              <td>
                <label v-for="role in ROLES" :key="role" class="check role-check">
                  <input
                    type="checkbox"
                    :checked="(roleDraft[member.userId] || []).includes(role)"
                    @change="toggleRole(roleDraft[member.userId], role)"
                  />
                  {{ memberRole[role] }}
                </label>
              </td>
              <td><span v-if="member.enabled" class="chip tone-done">启用</span><span v-else class="chip tone-muted">停用</span></td>
              <td class="nowrap">
                <button class="btn sm primary" :disabled="busy" @click="saveRoles(member)">保存角色</button>
                <button class="btn sm danger" @click="removeMember(member)">移出</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="panel-b row">
        <select v-model="memberForm.userId" style="width: 220px">
          <option value="" disabled>选择账号</option>
          <option v-for="user in addable" :key="user.id" :value="String(user.id)">
            {{ user.name }}（{{ user.account }}）
          </option>
        </select>
        <label v-for="role in ROLES" :key="role" class="check role-check">
          <input type="checkbox" :checked="memberForm.roles.includes(role)" @change="toggleRole(memberForm.roles, role)" />
          {{ memberRole[role] }}
        </label>
        <button class="btn primary" :disabled="busy || !memberForm.userId || !memberForm.roles.length" @click="addMember">
          加入小队
        </button>
        <span class="small muted">加入新小队会自动移出原来的小队。</span>
      </div>
    </div>

    <div class="panel">
      <div class="panel-h">账号</div>
      <div v-if="!users.length" class="empty">还没有账号。</div>
      <div v-else class="panel-b tight">
        <table class="table">
          <thead>
            <tr>
              <th>姓名</th>
              <th style="width: 150px">账号</th>
              <th style="width: 130px">全局角色</th>
              <th style="width: 90px">状态</th>
              <th style="width: 200px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="user in users" :key="user.id">
              <td class="title">{{ user.name }}</td>
              <td class="small muted">{{ user.account }}</td>
              <td class="small">{{ globalRole[user.globalRole] }}</td>
              <td><span v-if="user.enabled" class="chip tone-done">启用</span><span v-else class="chip tone-muted">停用</span></td>
              <td class="nowrap">
                <button class="btn sm" :disabled="busy" @click="toggleUser(user)">{{ user.enabled ? '停用' : '启用' }}</button>
                <button class="btn sm" :disabled="busy" @click="resetPassword(user)">重置密码</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="panel-b row">
        <input v-model="userForm.account" placeholder="登录账号" style="width: 150px" />
        <input v-model="userForm.name" placeholder="姓名" style="width: 130px" />
        <input v-model="userForm.password" type="password" placeholder="初始密码（≥6 位）" style="width: 180px" />
        <select v-model="userForm.globalRole" style="width: 150px">
          <option value="USER">成员</option>
          <option value="SUPERVISOR">跨队管理</option>
          <option value="ADMIN">系统管理员</option>
        </select>
        <button class="btn primary" :disabled="busy || !userForm.account || !userForm.name || !userForm.password" @click="createUser">
          新建账号
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.role-check { margin-right: 10px; font-size: 12.5px; }
</style>
