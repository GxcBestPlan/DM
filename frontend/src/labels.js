export const requirementStatus = {
  PENDING_REVIEW: { label: '待评审', tone: 'wait' },
  REVIEWED: { label: '已评审', tone: 'queue' },
  PLANNED: { label: '已排期', tone: 'plain' },
  DEV: { label: '开发中', tone: 'live' },
  READY_FOR_TEST: { label: '待测试', tone: 'wait' },
  TESTING: { label: '测试中', tone: 'live' },
  ACCEPTED: { label: '已验收', tone: 'done' },
  PUBLISHED: { label: '已发布', tone: 'done' },
  CANCELLED: { label: '已取消', tone: 'muted' },
}

export const taskStatus = {
  TODO: { label: '待开始', tone: 'plain' },
  IN_PROGRESS: { label: '进行中', tone: 'live' },
  DONE: { label: '已完成', tone: 'done' },
}

export const sprintStatus = {
  PLANNED: { label: '规划中', tone: 'plain' },
  ACTIVE: { label: '进行中', tone: 'live' },
  CLOSED: { label: '已关闭', tone: 'muted' },
}

export const requirementType = {
  FEATURE: '新功能',
  BUG: '缺陷修复',
  OPTIMIZATION: '优化',
  TECH_DEBT: '技术债',
}

export const memberRole = {
  PM: '产品经理',
  DEV_MANAGER: '开发管理者',
  FRONTEND_DEV: '前端开发',
  BACKEND_DEV: '后端开发',
  TESTER: '测试',
}

export const globalRole = {
  USER: '成员',
  SUPERVISOR: '跨队管理',
  ADMIN: '系统管理员',
}

export const taskType = { FRONTEND: '前端', BACKEND: '后端' }

/** 迭代内需求的看板列顺序（不含已取消）。 */
export const boardColumns = [
  'PLANNED',
  'DEV',
  'READY_FOR_TEST',
  'TESTING',
  'ACCEPTED',
  'PUBLISHED',
]

export function label(map, code) {
  const hit = map[code]
  if (!hit) return code || ''
  return typeof hit === 'string' ? hit : hit.label
}

export function tone(map, code) {
  const hit = map[code]
  return hit && hit.tone ? hit.tone : 'plain'
}

export function rolesText(roles) {
  return (roles || []).map((r) => memberRole[r] || r).join('、')
}
