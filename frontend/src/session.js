import { reactive } from 'vue'
import { api, getToken, setToken } from './api'

export const session = reactive({ user: null, ready: false })

export async function login(account, password) {
  const res = await api('/auth/login', { method: 'POST', body: { account, password } })
  setToken(res.token)
  session.user = res.user
  session.ready = true
  return res.user
}

export function logout() {
  setToken('')
  session.user = null
}

export async function restore() {
  if (getToken()) {
    try {
      session.user = await api('/auth/me')
    } catch {
      setToken('')
    }
  }
  session.ready = true
}

export function hasRole(...wanted) {
  const user = session.user
  if (!user) return false
  if (user.globalRole === 'ADMIN') return true
  return wanted.some((role) => (user.roles || []).includes(role))
}

export function isAdmin() {
  return !!session.user && session.user.globalRole === 'ADMIN'
}

export function isSupervisor() {
  return !!session.user && session.user.globalRole === 'SUPERVISOR'
}

export function readOnly() {
  return isSupervisor()
}
