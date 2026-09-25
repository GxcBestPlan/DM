-- DM 领域模型建表脚本（依据 docs/design.md，MySQL 8.0 / utf8mb4）
-- 启动时由 spring.sql.init.mode=always 执行，可重复执行（IF NOT EXISTS）。

CREATE TABLE IF NOT EXISTS sys_user (
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

CREATE TABLE IF NOT EXISTS team (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  name        VARCHAR(50)  NOT NULL,
  description VARCHAR(255) NULL,
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='小队';

CREATE TABLE IF NOT EXISTS team_member (
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

CREATE TABLE IF NOT EXISTS sprint (
  id         BIGINT       NOT NULL AUTO_INCREMENT,
  team_id    BIGINT       NOT NULL,
  name       VARCHAR(100) NOT NULL,
  start_date DATE         NOT NULL,
  end_date   DATE         NOT NULL,
  status     VARCHAR(20)  NOT NULL DEFAULT 'PLANNED' COMMENT 'PLANNED/ACTIVE/CLOSED',
  closed_at  DATETIME     NULL,
  created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_team (team_id),
  CONSTRAINT fk_sp_team FOREIGN KEY (team_id) REFERENCES team (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='迭代';

CREATE TABLE IF NOT EXISTS requirement (
  id                  BIGINT        NOT NULL AUTO_INCREMENT,
  team_id             BIGINT        NOT NULL,
  sprint_id           BIGINT        NULL COMMENT 'NULL=在需求池',
  title               VARCHAR(200)  NOT NULL,
  description         TEXT          NULL,
  type                VARCHAR(20)   NOT NULL COMMENT 'FEATURE/BUG/OPTIMIZATION/TECH_DEBT',
  status              VARCHAR(20)   NOT NULL DEFAULT 'PENDING_REVIEW',
  pool_seq            INT           NULL COMMENT '池内排序号，入迭代置空',
  estimate            DECIMAL(5,1)  NOT NULL COMMENT '预估人日，0.5 粒度且 >= 0.5',
  urgent              TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '插队标记',
  blocked             TINYINT(1)    NOT NULL DEFAULT 0,
  blocked_reason      VARCHAR(500)  NULL,
  blocked_at          DATETIME      NULL COMMENT '用于统计阻塞时长',
  acceptance_criteria TEXT          NULL COMMENT '验收标准，评审通过前必填',
  external_note       VARCHAR(500)  NULL COMMENT '外部依赖/跨队协调备注(选填)',
  created_by          BIGINT        NOT NULL,
  created_at          DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at          DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_team_pool (team_id, pool_seq),
  KEY idx_sprint (sprint_id),
  KEY idx_team_status (team_id, status),
  CONSTRAINT fk_req_team   FOREIGN KEY (team_id)    REFERENCES team (id),
  CONSTRAINT fk_req_sprint FOREIGN KEY (sprint_id)  REFERENCES sprint (id),
  CONSTRAINT fk_req_user   FOREIGN KEY (created_by) REFERENCES sys_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='需求';

CREATE TABLE IF NOT EXISTS task (
  id                 BIGINT       NOT NULL AUTO_INCREMENT,
  requirement_id     BIGINT       NOT NULL,
  type               VARCHAR(10)  NOT NULL COMMENT 'FRONTEND/BACKEND',
  title              VARCHAR(200) NOT NULL,
  assignee_id        BIGINT       NULL COMMENT '负责人，空=未分配',
  status             VARCHAR(20) NOT NULL DEFAULT 'TODO' COMMENT 'TODO/IN_PROGRESS/DONE',
  sort               INT          NOT NULL DEFAULT 0,
  progress_note      VARCHAR(500) NULL COMMENT '最新进展备注',
  planned_start_date DATE         NULL COMMENT '管道排期起始日',
  planned_end_date   DATE         NULL COMMENT '管道排期结束日',
  blocked            TINYINT(1)   NOT NULL DEFAULT 0,
  blocked_reason     VARCHAR(500) NULL,
  blocked_at         DATETIME     NULL,
  completed_at       DATETIME     NULL,
  created_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_requirement (requirement_id),
  KEY idx_assignee (assignee_id, status),
  KEY idx_assignee_date (assignee_id, planned_start_date),
  CONSTRAINT fk_tk_req    FOREIGN KEY (requirement_id) REFERENCES requirement (id),
  CONSTRAINT fk_tk_assign FOREIGN KEY (assignee_id)    REFERENCES sys_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务';

CREATE TABLE IF NOT EXISTS task_dependency (
  id                 BIGINT   NOT NULL AUTO_INCREMENT,
  task_id            BIGINT   NOT NULL COMMENT '依赖方(前端任务)',
  depends_on_task_id BIGINT   NOT NULL COMMENT '被依赖方(后端任务)',
  created_at         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_dep (task_id, depends_on_task_id),
  KEY idx_depends (depends_on_task_id),
  CONSTRAINT fk_dep_task      FOREIGN KEY (task_id)            REFERENCES task (id),
  CONSTRAINT fk_dep_dependson FOREIGN KEY (depends_on_task_id) REFERENCES task (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务依赖';

CREATE TABLE IF NOT EXISTS status_log (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  object_type VARCHAR(20)  NOT NULL COMMENT 'REQUIREMENT/TASK',
  object_id   BIGINT       NOT NULL,
  from_status VARCHAR(20)  NULL COMMENT 'NULL=创建',
  to_status   VARCHAR(20)  NOT NULL,
  comment     VARCHAR(500) NULL COMMENT '评审意见/退回原因/阻塞原因等',
  operator_id BIGINT       NULL COMMENT 'NULL=系统自动',
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_obj (object_type, object_id),
  KEY idx_time (created_at),
  CONSTRAINT fk_sl_user FOREIGN KEY (operator_id) REFERENCES sys_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='状态日志';
