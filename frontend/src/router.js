import { createRouter, createWebHashHistory } from 'vue-router'
import { session } from './session'

const routes = [
  { path: '/login', component: () => import('./views/Login.vue'), meta: { public: true } },
  { path: '/', redirect: () => homeFor(session.user) },
  { path: '/w', component: () => import('./views/Workbench.vue') },
  { path: '/pool', component: () => import('./views/Pool.vue'), meta: { roles: ['PM'] } },
  { path: '/sprints', component: () => import('./views/Sprints.vue'), meta: { roles: ['DEV_MANAGER'] } },
  { path: '/board', component: () => import('./views/Board.vue') },
  { path: '/r/:id', component: () => import('./views/RequirementDetail.vue') },
  {
    path: '/pipeline',
    component: () => import('./views/Pipeline.vue'),
    meta: { roles: ['DEV_MANAGER'], global: ['SUPERVISOR'] },
  },
  {
    path: '/mine',
    component: () => import('./views/MyTasks.vue'),
    meta: { roles: ['FRONTEND_DEV', 'BACKEND_DEV', 'DEV_MANAGER'] },
  },
  {
    path: '/testing',
    component: () => import('./views/Testing.vue'),
    meta: { roles: ['TESTER', 'DEV_MANAGER'] },
  },
  { path: '/overview', component: () => import('./views/Overview.vue'), meta: { global: ['SUPERVISOR', 'ADMIN'] } },
  {
    path: '/reports',
    component: () => import('./views/Reports.vue'),
    meta: { roles: ['PM', 'DEV_MANAGER'], global: ['SUPERVISOR', 'ADMIN'] },
  },
  { path: '/org', component: () => import('./views/Org.vue'), meta: { global: ['ADMIN'] } },
  { path: '/:pathMatch(.*)*', redirect: () => homeFor(session.user) },
]

export function canAccess(meta, user) {
  if (!meta) return true
  if (meta.public) return true
  if (!user) return false
  if (user.globalRole === 'ADMIN') return true
  const roleRule = Array.isArray(meta.roles) && meta.roles.length > 0
  const globalRule = Array.isArray(meta.global) && meta.global.length > 0
  // 没有声明任何限制的路由，登录后即可访问
  if (!roleRule && !globalRule) return true
  if (globalRule && meta.global.includes(user.globalRole)) return true
  if (roleRule && meta.roles.some((role) => (user.roles || []).includes(role))) return true
  return false
}

export function homeFor(user) {
  if (!user) return '/login'
  if (user.globalRole === 'SUPERVISOR' || user.globalRole === 'ADMIN') return '/overview'
  const roles = user.roles || []
  if (roles.includes('PM')) return '/pool'
  if (roles.includes('DEV_MANAGER')) return '/sprints'
  if (roles.includes('TESTER')) return '/testing'
  return '/w'
}

const router = createRouter({
  history: createWebHashHistory(),
  routes,
})

router.beforeEach((to) => {
  const user = session.user
  if (to.meta.public) return user ? homeFor(user) : true
  if (!user) return { path: '/login' }
  if (!canAccess(to.meta, user)) return homeFor(user)
  return true
})

export default router
