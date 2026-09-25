# DM 领域模型与数据库设计（v1.0）

| 版本 | 日期 | 说明 | 状态 |
|---|---|---|---|
| v1.0 | 2026-09-06 | 确认 §6 四项取舍（D1–D4）后定稿 | **定稿（可进入实现）** |

依据：`docs/requirements.md` v1.0（含 §14 八项评审决策）。设计目标 = 最小可支撑全部 MVP 页面与规则的数据模型，不含本期范围外能力。

## 1. 领域模型总览

```
team 1──n team_member n──1 sys_user       用户可多角色（每角色一行）；属队唯一性在服务层保证
team 1──n sprint 1──n requirement         需求 sprint_id=NULL 时在需求池，否则在某迭代
requirement 1──n task n──1 sys_user       任务负责人 assignee（可空=未分配）
task n──m task_dependency                 依赖：仅 FRONTEND→BACKEND、同需求内、禁环（服务层校验）
requirement/task 1──n status_log          全量状态流转留痕（意见/退回原因/阻塞原因放 comment）
```

设计要点：

- **需求状态一个字段走完全生命周期**（池内两态 + 迭代内六态 + 已取消），"在池/在迭代"由 `sprint_id` 是否为 NULL 决定，不再拆分表。
- **阻塞是覆盖层不是状态**：任意状态下可挂阻塞（`blocked` + 原因 + `blocked_at`），解除后回到原状态，便于统计阻塞时长，无需为阻塞建额外状态。
- **评审退回、测试退回、挂/解阻塞**不引入额外实体，一律写 `status_log`（自环或状态迁移 + `comment` 存意见/原因），页面从日志取最近一条展示。
- **预估人日只在需求级**（任务不拆估人日，v1.0 核心对象任务无此字段）。
- 存量 demo 表 `item` 与 `ItemController/ItemRepository/Item` 随真实模型落地一起删除。

## 2. 枚举定义（库内存 code，前端映射中文）

| 枚举 | 取值 |
|---|---|
| 用户全局角色 `global_role` | `USER` 成员 / `SUPERVISOR` 跨队管理者 / `ADMIN` 系统管理员 |
| 队内角色 `team_member.role` | `PM` / `DEV_MANAGER` / `FRONTEND_DEV` / `BACKEND_DEV` / `TESTER` |
| 需求类型 `requirement.type` | `FEATURE` 新功能 / `BUG` 缺陷修复 / `OPTIMIZATION` 优化 / `TECH_DEBT` 技术债 |
| 需求状态 `requirement.status` | `PENDING_REVIEW` 待评审 → `REVIEWED` 已评审 → `PLANNED` 已排期 → `DEV` 开发中 → `READY_FOR_TEST` 待测试 → `TESTING` 测试中 → `ACCEPTED` 已验收 → `PUBLISHED` 已发布；终态 `CANCELLED` 已取消 |
| 任务类型 `task.type` | `FRONTEND` / `BACKEND` |
| 任务状态 `task.status` | `TODO` 待开始 → `IN_PROGRESS` 进行中 → `DONE` 已完成 |
| 迭代状态 `sprint.status` | `PLANNED` 规划中 → `ACTIVE` 进行中 → `CLOSED` 已关闭 |
| 日志对象 `status_log.object_type` | `REQUIREMENT` / `TASK` |

## 3. 表结构（DDL）

库 `dm`（utf8mb4），所有表 `id BIGINT AUTO_INCREMENT`，`created_at/updated_at` 由 DB 默认值维护（JPA 不写）。

```sql
-- 用户
CREATE TABLE sys_user (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  account       VARCHAR(50)  NOT NULL COMMENT '登录账号',
  password_hash VARCHAR(100) NOT NULL COMMENT 'BCrypt',
  name          VARCHAR(50)  NOT NULL,
  global_role   VARCHAR(20)  NOT NULL DEFAULT 'USER' COMMENT 'USER/SUPERVISOR/ADMIN',
  enabled       TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '停用=0',
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_account (account)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户';

-- 小队
CREATE TABLE team (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  name        VARCHAR(50)  NOT NULL,
  description VARCHAR(255) NULL,
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='小队';

-- 队内成员角色（一人可多角色，每角色一行；一行即"属于该队"）
CREATE TABLE team_member (
  id         BIGINT      NOT NULL AUTO_INCREMENT,
  team_id    BIGINT      NOT NULL,
  user_id    BIGINT      NOT NULL,
  role       VARCHAR(20) NOT NULL COMMENT 'PM/DEV_MANAGER/FRONTEND_DEV/BACKEND_DEV/TESTER',
  created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_team_user_role (team_id, user_id, role),
  KEY idx_user (user_id),
  CONSTRAINT fk_tm_team FOREIGN KEY (team_id) REFERENCES team (id),
  CONSTRAINT fk_tm_user FOREIGN KEY (user_id) REFERENCES sys_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='队内成员角色';

-- 迭代
CREATE TABLE sprint (
  id         BIGINT       NOT NULL AUTO_INCREMENT,
  team_id    BIGINT       NOT NULL,
  name       VARCHAR(100) NOT NULL COMMENT '如 V2.3 / 2026-W37~38',
  start_date DATE         NOT NULL,
  end_date   DATE         NOT NULL,
  status     VARCHAR(20)  NOT NULL DEFAULT 'PLANNED' COMMENT 'PLANNED/ACTIVE/CLOSED',
  closed_at  DATETIME     NULL,
  created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_team (team_id),
  CONSTRAINT fk_sp_team FOREIGN KEY (team_id) REFERENCES team (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='迭代';

-- 需求（sprint_id 空=在需求池）
CREATE TABLE requirement (
  id                 BIGINT        NOT NULL AUTO_INCREMENT,
  team_id            BIGINT        NOT NULL,
  sprint_id          BIGINT        NULL COMMENT 'NULL=在需求池',
  title              VARCHAR(200)  NOT NULL,
  description        TEXT          NULL,
  type               VARCHAR(20)   NOT NULL COMMENT 'FEATURE/BUG/OPTIMIZATION/TECH_DEBT',
  status             VARCHAR(20)   NOT NULL DEFAULT 'PENDING_REVIEW' COMMENT '见需求状态枚举',
  pool_seq           INT           NULL COMMENT '池内排序号(同队池内唯一, 入迭代置空)',
  estimate           DECIMAL(5,1)  NOT NULL COMMENT '预估人日, 0.5 粒度且 >= 0.5',
  urgent             TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '插队标记',
  blocked            TINYINT(1)    NOT NULL DEFAULT 0,
  blocked_reason     VARCHAR(500)  NULL,
  blocked_at         DATETIME      NULL COMMENT '用于统计阻塞时长',
  acceptance_criteria TEXT         NULL COMMENT '验收标准, 登记评审通过前必填',
  external_note      VARCHAR(500)  NULL COMMENT '外部依赖/跨队协调备注(选填)',
  created_by         BIGINT        NOT NULL,
  created_at         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_team_pool (team_id, pool_seq),
  KEY idx_sprint (sprint_id),
  KEY idx_team_status (team_id, status),
  CONSTRAINT fk_req_team   FOREIGN KEY (team_id)   REFERENCES team (id),
  CONSTRAINT fk_req_sprint FOREIGN KEY (sprint_id) REFERENCES sprint (id),
  CONSTRAINT fk_req_user   FOREIGN KEY (created_by) REFERENCES sys_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='需求';

-- 任务
CREATE TABLE task (
  id                BIGINT       NOT NULL AUTO_INCREMENT,
  requirement_id    BIGINT       NOT NULL,
  type              VARCHAR(10)  NOT NULL COMMENT 'FRONTEND/BACKEND',
  title             VARCHAR(200) NOT NULL,
  assignee_id       BIGINT       NULL COMMENT '负责人, 空=未分配',
  status            VARCHAR(10)  NOT NULL DEFAULT 'TODO' COMMENT 'TODO/IN_PROGRESS/DONE',
  sort              INT          NOT NULL DEFAULT 0 COMMENT '需求内展示排序',
  progress_note     VARCHAR(500) NULL COMMENT '最新进展备注(一句话)',
  planned_start_date DATE        NULL COMMENT '管道排期起始日',
  planned_end_date   DATE        NULL COMMENT '管道排期结束日',
  blocked           TINYINT(1)   NOT NULL DEFAULT 0,
  blocked_reason    VARCHAR(500) NULL,
  blocked_at        DATETIME     NULL,
  completed_at      DATETIME     NULL COMMENT '实际完成时间',
  created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_requirement (requirement_id),
  KEY idx_assignee (assignee_id, status),
  KEY idx_assignee_date (assignee_id, planned_start_date),
  CONSTRAINT fk_tk_req     FOREIGN KEY (requirement_id) REFERENCES requirement (id),
  CONSTRAINT fk_tk_assign  FOREIGN KEY (assignee_id)    REFERENCES sys_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务';

-- 任务依赖（仅 FRONTEND 依赖 BACKEND, 同需求内, 无环 —— 服务层校验）
CREATE TABLE task_dependency (
  id                 BIGINT NOT NULL AUTO_INCREMENT,
  task_id            BIGINT NOT NULL COMMENT '依赖方(前端任务)',
  depends_on_task_id BIGINT NOT NULL COMMENT '被依赖方(后端任务)',
  created_at         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_dep (task_id, depends_on_task_id),
  KEY idx_depends (depends_on_task_id),
  CONSTRAINT fk_dep_task      FOREIGN KEY (task_id)            REFERENCES task (id),
  CONSTRAINT fk_dep_dependson FOREIGN KEY (depends_on_task_id) REFERENCES task (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务依赖';

-- 状态日志（需求/任务全量流转；意见/原因放 comment；自环表示挂阻塞/解除/评审退回等不换状态的留痕）
CREATE TABLE status_log (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  object_type VARCHAR(10)  NOT NULL COMMENT 'REQUIREMENT/TASK',
  object_id   BIGINT       NOT NULL,
  from_status VARCHAR(20)  NULL COMMENT 'NULL=对象创建',
  to_status   VARCHAR(20)  NOT NULL,
  comment     VARCHAR(500) NULL COMMENT '评审意见/退回原因/阻塞原因/进展备注留痕等',
  operator_id BIGINT       NULL COMMENT 'NULL=系统自动推进',
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_obj (object_type, object_id),
  KEY idx_time (created_at),
  CONSTRAINT fk_sl_user FOREIGN KEY (operator_id) REFERENCES sys_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='状态日志';
```

## 4. 状态流转与触发规则

### 4.1 需求（池内）

| 动作 | 迁移 | 触发/校验 |
|---|---|---|
| 录入 | → `PENDING_REVIEW` | PM；`estimate` 必填(≥0.5、0.5 倍数)，`pool_seq` 排到队尾 |
| 评审通过 | `PENDING_REVIEW`→`REVIEWED` | 线下结论登记；**`acceptance_criteria` 非空**（决策 5）；意见可写日志 |
| 评审退回 | 状态不变 | 仅写日志（自环，`comment`=退回意见，决策记录方式待确认 D3） |
| 拖拽定序 | 不变 | PM；事务内重写同队池内 `pool_seq` 1..n |
| 排入迭代 | `REVIEWED`→`PLANNED` | 开发管理者；设 `sprint_id`、`pool_seq=NULL` |
| 移出迭代 | `PLANNED`→`REVIEWED` | 迭代 `PLANNED` 期可撤；`pool_seq` 重排到队尾 |
| 插队入当前迭代 | `REVIEWED`→`PLANNED` | 目标迭代必须 `ACTIVE`；`urgent=1`；前端展示负载影响提示，不强制顺延（决策 4） |
| 取消 | 任意非终态→`CANCELLED` | 池内 PM、迭代内开发管理者；保留记录供统计 |

### 4.2 需求（迭代内）

| 迁移 | 触发 |
|---|---|
| `PLANNED`→`DEV` | 首个任务标记"进行中"时自动转开发中（D1） |
| `DEV`→`READY_FOR_TEST` | 最后一个任务标记"已完成"时自动转待测试（D2）；无任务需求由开发管理者手动兜底 |
| `READY_FOR_TEST`→`TESTING` | 测试人员领取 |
| `TESTING`→`ACCEPTED` | 测试通过 |
| `TESTING`→`DEV` | 测试退回，`comment`(原因) 必填（规则 10） |
| `ACCEPTED`→`PUBLISHED` | 开发管理者手动标记（决策 6） |

阻塞覆盖层：任意非终态挂/解阻塞，`blocked_reason` 必填、记 `blocked_at`，各写一条自环日志（原因入 comment，规则 7）。

### 4.3 任务

| 迁移 | 触发/校验 |
|---|---|
| `TODO`→`IN_PROGRESS` | 负责人本人；所属迭代须 `ACTIVE`；需求若 `PLANNED` 同步自动转 `DEV` |
| `IN_PROGRESS`→`DONE` | 负责人本人；写 `completed_at`；被依赖(后端)任务未完成时给出提示、**不阻断**（规则 5）；若是最后一个任务，需求自动 `DEV`→`READY_FOR_TEST` |
| 回退/改派 | `TODO`↔`IN_PROGRESS` 仅开发管理者可重置；改派=改 `assignee_id`（日志留痕） |

### 4.4 迭代

`PLANNED`→`ACTIVE`（开发管理者开始）→`CLOSED`（开发管理者关闭，需求/任务只读）；`CLOSED`→`ACTIVE`（"重新打开"，开发管理者，数据保留，规则 9）。

## 5. 服务层校验清单（需求规则 → 落点）

| 需求规则 | 落点 |
|---|---|
| 状态变更必写日志（谁/何时/从哪到哪） | 服务层统一 transition 助手，禁止绕过；`operator_id` 空=系统自动 |
| 迭代 `CLOSED` 后需求/任务只读 | 变更入口统一守卫：所属迭代 `CLOSED` 直接拒绝 |
| 阻塞必有原因、解除要留痕 | 挂/解阻塞校验 + 自环日志 |
| 测试/评审退回必填原因/意见 | transition 校验 `comment` 非空 |
| 依赖仅 FRONTEND→BACKEND、禁环、同需求内 | 增删依赖接口校验（新增时沿 `depends_on` 反向查环） |
| 一人属一队 | 成员管理接口：加入新队先移除旧队所有行 |
| 预估人日 ≥0.5 且 0.5 倍数 | 需求录入/编辑校验 |
| 任务开始须所属迭代 `ACTIVE` | 任务状态迁移校验 |
| 管道冲突高亮 | 同 `assignee_id` 且日期区间重叠的任务数 ≥2 高亮（D4：本期不做超载红线预警） |

## 6. 取舍决策记录（v1.0，2026-09-06）

| # | 取舍点 | 结论 |
|---|---|---|
| D1 | 需求"已排期"→"开发中"触发 | **首个任务标记"进行中"即自动转**（开发人员≤2 步） |
| D2 | 需求"开发中"→"待测试"触发 | **最后一个任务标记"已完成"即自动转**；无任务需求由开发管理者手动兜底 |
| D3 | 评审退回意见留痕 | **状态日志自环**（`PENDING_REVIEW`→`PENDING_REVIEW` + comment），不建评审实体、需求表不加字段 |
| D4 | 管道冲突/超载判定 | MVP **仅高亮同日任务区间重叠 ≥2**；"周负载超限"红线阈值未定义，本期只展示计划负载，不做自动超载预警 |

## 7. 落地顺序（确认后执行）

1. 后端：按本 DDL 建实体 + Repository + DDL 脚本（`schema.sql` 或启动 `ddl-auto` 对齐），删 demo `item`
2. 后端：状态机 transition 服务 + 校验清单单元化（先做需求池与迭代管理闭环）
3. 前端：路由 + 登录 + 需求池页 → 迭代管理 → 迭代看板 → 其余页面
