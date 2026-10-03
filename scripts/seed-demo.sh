#!/usr/bin/env bash
# 生成一套可用来验收/演示的数据：一个小队、6 个账号、进行中的迭代、需求与任务。
# 用法：先启动后端，再执行 scripts/seed-demo.sh
#
# 流程刻意按真实节奏走：先建迭代（规划中）→ 录需求、评审、排期、拆任务 → 再开始迭代 → 推进任务状态。
set -euo pipefail

BASE="${BASE:-http://localhost:8080/api}"
ADMIN_ACCOUNT="${ADMIN_ACCOUNT:-admin}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-admin123}"
TEAM_NAME="交易研发一队"
PASSWORD="pw123456"

field() { python3 -c "import sys,json;print(json.load(sys.stdin)$1)"; }

api() {
  local method="$1" path="$2" token="${3:-}" body="${4:-}"
  if [ -n "$body" ]; then
    curl -s -X "$method" "$BASE$path" -H "Authorization: Bearer $token" \
      -H 'Content-Type: application/json' -d "$body"
  else
    curl -s -X "$method" "$BASE$path" -H "Authorization: Bearer $token"
  fi
}

# 写操作失败时立刻报错，避免留下半套数据
api_ok() {
  local out
  out="$(api "$@")"
  case "$out" in
    *'"error"'*) echo "接口报错：$* -> $out" >&2; exit 1 ;;
  esac
  printf '%s' "$out"
}

login() {
  curl -s -X POST "$BASE/auth/login" -H 'Content-Type: application/json' \
    -d "{\"account\":\"$1\",\"password\":\"$2\"}" | field "['token']"
}

ADMIN="$(login "$ADMIN_ACCOUNT" "$ADMIN_PASSWORD")"
if [ -z "$ADMIN" ]; then
  echo "无法登录管理员，请确认后端已启动且账号为 $ADMIN_ACCOUNT / $ADMIN_PASSWORD" >&2
  exit 1
fi

if api GET /org/teams "$ADMIN" | grep -q "$TEAM_NAME"; then
  echo "已存在「$TEAM_NAME」，跳过演示数据生成。"
  exit 0
fi

echo "1/5 创建小队与账号"
TEAM_ID="$(api_ok POST /org/teams "$ADMIN" "{\"name\":\"$TEAM_NAME\",\"description\":\"交易链路与订单相关需求\"}" | field "['id']")"

create_user() { api_ok POST /org/users "$ADMIN" "{\"account\":\"$1\",\"name\":\"$2\",\"password\":\"$PASSWORD\",\"globalRole\":\"$3\"}" | field "['id']"; }
add_member() { api_ok POST "/org/teams/$TEAM_ID/members" "$ADMIN" "{\"userId\":$1,\"roles\":[\"$2\"]}" > /dev/null; }

PM_ID="$(create_user lijing 李静 USER)"
DM_ID="$(create_user wangqiang 王强 USER)"
FE_ID="$(create_user zhaolei 赵磊 USER)"
BE_ID="$(create_user suntao 孙涛 USER)"
QA_ID="$(create_user zhoumin 周敏 USER)"
create_user chenzong 陈总 SUPERVISOR > /dev/null

add_member "$PM_ID" PM
add_member "$DM_ID" DEV_MANAGER
add_member "$FE_ID" FRONTEND_DEV
add_member "$BE_ID" BACKEND_DEV
add_member "$QA_ID" TESTER

PM="$(login lijing "$PASSWORD")"
DM="$(login wangqiang "$PASSWORD")"
FE="$(login zhaolei "$PASSWORD")"
BE="$(login suntao "$PASSWORD")"
QA="$(login zhoumin "$PASSWORD")"

TODAY="$(date +%F)"
END="$(date -v+13d +%F)"
D1="$(date -v+1d +%F)"
D2="$(date -v+2d +%F)"
D3="$(date -v+3d +%F)"
D4="$(date -v+4d +%F)"

echo "2/5 创建迭代（规划中）"
SPRINT_ID="$(api_ok POST /sprints "$DM" "{\"name\":\"V2.3\",\"startDate\":\"$TODAY\",\"endDate\":\"$END\"}" | field "['id']")"

create_req() { # 标题 类型 预估 验收标准 -> 已评审需求 id
  local id
  id="$(api_ok POST /requirements "$PM" "{\"title\":\"$1\",\"type\":\"$2\",\"estimate\":$3,\"acceptanceCriteria\":\"$4\"}" | field "['id']")"
  api_ok POST "/requirements/$id/review" "$PM" '{"approved":true}' > /dev/null
  echo "$id"
}
schedule() { api_ok POST "/requirements/$1/schedule" "$DM" "{\"sprintId\":$SPRINT_ID,\"urgent\":false}" > /dev/null; }
add_task() { api_ok POST "/requirements/$1/tasks" "$DM" "{\"type\":\"$2\",\"title\":\"$3\",\"assigneeId\":$4}" | field "['id']"; }
place_task() { api_ok PUT "/tasks/$1/schedule" "$DM" "{\"assigneeId\":$2,\"plannedStartDate\":\"$3\",\"plannedEndDate\":\"$4\"}" > /dev/null; }

echo "3/5 录需求、评审、排期、拆任务"
R1="$(create_req '订单列表支持批量导出' FEATURE 3.5 '1. 导出内容与筛选一致；2. 超过 5000 条走异步任务；3. 导出操作记入日志')"
schedule "$R1"
T1="$(add_task "$R1" BACKEND '导出接口（异步任务）' "$BE_ID")"
T2="$(add_task "$R1" FRONTEND '导出弹窗与进度提示' "$FE_ID")"
place_task "$T1" "$BE_ID" "$TODAY" "$D1"
place_task "$T2" "$FE_ID" "$D1" "$D3"
api_ok POST "/tasks/$T2/dependencies" "$DM" "{\"dependsOnTaskId\":$T1}" > /dev/null

R2="$(create_req '支付回调偶发超时修复' BUG 1.5 '回调重试后成功率 100%，超时告警消除')"
schedule "$R2"
T3="$(add_task "$R2" BACKEND '回调重试与超时告警' "$BE_ID")"
place_task "$T3" "$BE_ID" "$TODAY" "$D1"

R3="$(create_req '首页加载性能优化' OPTIMIZATION 2.0 '首屏小于 1.5 秒，LCP 达标')"
schedule "$R3"
T4="$(add_task "$R3" FRONTEND '首屏资源懒加载' "$FE_ID")"
place_task "$T4" "$FE_ID" "$TODAY" "$D2"
# 与下一个任务在 D1~D2 重叠，用来演示管道冲突高亮
T5="$(add_task "$R3" FRONTEND '性能埋点补全' "$FE_ID")"
place_task "$T5" "$FE_ID" "$D1" "$D2"

R4="$(create_req '老对账接口技术债清理' TECH_DEBT 5.0 '对账结果与旧接口一致，回归通过')"
schedule "$R4"
T6="$(add_task "$R4" BACKEND '对账逻辑重构' "$BE_ID")"
place_task "$T6" "$BE_ID" "$D2" "$D4"

R5="$(create_req '用户协议文案更新' OPTIMIZATION 0.5 '新文案上线且页面无残留旧文案')"
schedule "$R5"
T7="$(add_task "$R5" FRONTEND '协议页文案替换' "$FE_ID")"
place_task "$T7" "$FE_ID" "$TODAY" "$D1"

R6="$(create_req '登录验证码支持短信' FEATURE 1.0 '短信验证码 60 秒内有效，可重发')"
schedule "$R6"
T8="$(add_task "$R6" BACKEND '短信验证码接口' "$BE_ID")"
place_task "$T8" "$BE_ID" "$TODAY" "$D1"

R7="$(create_req '订单导出字段权限校验' TECH_DEBT 0.5 '无导出权限的账号拿不到敏感字段')"
schedule "$R7"
T9="$(add_task "$R7" BACKEND '导出字段权限拦截' "$BE_ID")"
place_task "$T9" "$BE_ID" "$TODAY" "$D1"

echo "4/5 开始迭代，推进任务（需求状态自动流转）"
api_ok POST "/sprints/$SPRINT_ID/start" "$DM" > /dev/null

api_ok POST "/tasks/$T1/start" "$BE" > /dev/null
api_ok POST "/tasks/$T1/complete" "$BE" > /dev/null
api_ok POST "/tasks/$T2/start" "$FE" > /dev/null
api_ok POST "/tasks/$T2/progress" "$FE" '{"note":"弹窗已完成，等待导出接口返回样例数据"}' > /dev/null

api_ok POST "/tasks/$T3/start" "$BE" > /dev/null
api_ok POST "/tasks/$T3/complete" "$BE" > /dev/null

api_ok POST "/tasks/$T4/start" "$FE" > /dev/null
api_ok POST "/tasks/$T4/complete" "$FE" > /dev/null

api_ok POST "/requirements/$R4/block" "$DM" '{"reason":"等待基础平台队提供导出组件 v2"}' > /dev/null

api_ok POST "/tasks/$T7/start" "$FE" > /dev/null
api_ok POST "/tasks/$T7/complete" "$FE" > /dev/null
api_ok POST "/requirements/$R5/claim" "$QA" > /dev/null
api_ok POST "/requirements/$R5/accept" "$QA" > /dev/null
api_ok POST "/requirements/$R5/publish" "$DM" > /dev/null

api_ok POST "/tasks/$T8/start" "$BE" > /dev/null
api_ok POST "/tasks/$T8/complete" "$BE" > /dev/null
api_ok POST "/requirements/$R6/claim" "$QA" > /dev/null

api_ok POST "/tasks/$T9/start" "$BE" > /dev/null
api_ok POST "/tasks/$T9/complete" "$BE" > /dev/null
api_ok POST "/requirements/$R7/claim" "$QA" > /dev/null
api_ok POST "/requirements/$R7/accept" "$QA" > /dev/null

echo "5/5 需求池留下待评审 / 已评审各一条"
REVIEW_ID="$(api_ok POST /requirements "$PM" '{"title":"商家后台订单备注功能","type":"FEATURE","estimate":2.0,"acceptanceCriteria":"可新增/编辑/删除备注，仅本队可见"}' | field "['id']")"
POOL_ID="$(api_ok POST /requirements "$PM" '{"title":"优惠券过期提醒","type":"FEATURE","estimate":1.5}' | field "['id']")"
api_ok POST "/requirements/$REVIEW_ID/review" "$PM" '{"approved":false,"comment":"请补充提醒触达方式与频次上限"}' > /dev/null
api_ok PUT "/requirements/$POOL_ID" "$PM" '{"title":"优惠券过期提醒","type":"FEATURE","estimate":1.5,"acceptanceCriteria":"过期前 3 天提醒，仅提醒一次"}' > /dev/null
api_ok POST "/requirements/$POOL_ID/review" "$PM" '{"approved":true}' > /dev/null

cat <<'EOF'
完成。可用账号（密码均为 pw123456，管理员为 admin123）：
  admin      系统管理员
  chenzong   跨队管理者（只看总览与报表）
  lijing     产品经理（需求池）
  wangqiang  开发管理者（迭代管理、管道排期、报表）
  zhaolei    前端开发（我的任务）
  suntao     后端开发（我的任务）
  zhoumin    测试（测试工作台）
EOF
