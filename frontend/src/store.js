import { reactive } from 'vue'
import { api } from './api'
import { session } from './session'

/** 迭代上下文：顶栏、看板、管道、报表共用；避免每页重复拉取。 */
export const store = reactive({ sprints: [], current: null, loaded: false })

export async function loadSprints(teamId, force = false) {
  const scope = teamId || (session.user && session.user.teamId)
  if (!scope) {
    store.sprints = []
    store.current = null
    store.loaded = true
    return store.sprints
  }
  if (store.loaded && !force && store.teamId === scope) return store.sprints
  try {
    store.sprints = (await api(`/sprints?teamId=${scope}`)) || []
  } catch {
    store.sprints = []
  }
  store.teamId = scope
  store.current =
    store.sprints.find((s) => s.status === 'ACTIVE') ||
    store.sprints.find((s) => s.status === 'PLANNED') ||
    null
  store.loaded = true
  return store.sprints
}

export function clearStore() {
  store.sprints = []
  store.current = null
  store.loaded = false
  store.teamId = null
}

export function pickSprint(sprintId) {
  const id = Number(sprintId)
  return store.sprints.find((s) => s.id === id) || null
}
