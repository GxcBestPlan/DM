<script setup>
import { computed, ref } from 'vue'
import { post } from '../api'
import { hasRole, isAdmin, session } from '../session'

const props = defineProps({ task: { type: Object, required: true } })
const emit = defineEmits(['changed'])

const busy = ref(false)
const error = ref('')
const mode = ref('')
const text = ref('')

const canManage = computed(() => isAdmin() || hasRole('DEV_MANAGER'))
const isMine = computed(() => props.task.assigneeId === (session.user && session.user.id))
const may = computed(() => canManage.value || isMine.value)

async function run(action) {
  busy.value = true
  error.value = ''
  try {
    await action()
    mode.value = ''
    text.value = ''
    emit('changed')
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = false
  }
}

const start = () => run(() => post(`/tasks/${props.task.id}/start`))
const complete = () => run(() => post(`/tasks/${props.task.id}/complete`))
const saveNote = () => run(() => post(`/tasks/${props.task.id}/progress`, { note: text.value }))
const saveBlock = () => run(() => post(`/tasks/${props.task.id}/block`, { reason: text.value }))
const unblock = () => run(() => post(`/tasks/${props.task.id}/unblock`))
const toggle = (next) => {
  mode.value = mode.value === next ? '' : next
  text.value = ''
}
</script>

<template>
  <div class="acts">
    <template v-if="may">
      <button v-if="task.status === 'TODO'" class="btn sm primary" :disabled="busy" @click="start">开始</button>
      <button v-if="task.status === 'IN_PROGRESS'" class="btn sm primary" :disabled="busy" @click="complete">
        完成
      </button>
      <button class="btn sm" :disabled="busy" @click="toggle('note')">写备注</button>
      <button v-if="!task.blocked" class="btn sm danger" :disabled="busy" @click="toggle('block')">挂阻塞</button>
      <button v-else class="btn sm" :disabled="busy" @click="unblock">解除阻塞</button>
    </template>
    <span v-else class="muted small">仅负责人或开发管理者可更新</span>

    <div v-if="mode" class="inline">
      <input
        v-model="text"
        :placeholder="mode === 'note' ? '一句话说明今天的进展或卡点' : '阻塞原因（必填）'"
        @keyup.enter="mode === 'note' ? saveNote() : saveBlock()"
      />
      <button class="btn sm primary" :disabled="busy" @click="mode === 'note' ? saveNote() : saveBlock()">
        保存
      </button>
      <button class="btn sm" :disabled="busy" @click="mode = ''">取消</button>
    </div>
    <div v-if="error" class="err">{{ error }}</div>
  </div>
</template>

<style scoped>
.acts { display: flex; flex-wrap: wrap; gap: 6px; align-items: center; }
.inline { display: flex; gap: 6px; width: 100%; margin-top: 6px; }
.inline input { flex: 1; }
</style>
