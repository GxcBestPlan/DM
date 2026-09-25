package com.dm.backend;

import com.dm.backend.entity.Requirement;
import com.dm.backend.entity.Sprint;
import com.dm.backend.entity.StatusLog;
import com.dm.backend.entity.SysUser;
import com.dm.backend.entity.Task;
import com.dm.backend.entity.TaskDependency;
import com.dm.backend.entity.Team;
import com.dm.backend.entity.TeamMember;
import com.dm.backend.entity.enums.LogObjectType;
import com.dm.backend.entity.enums.MemberRole;
import com.dm.backend.entity.enums.RequirementStatus;
import com.dm.backend.entity.enums.RequirementType;
import com.dm.backend.entity.enums.TaskStatus;
import com.dm.backend.entity.enums.TaskType;
import com.dm.backend.repository.RequirementRepository;
import com.dm.backend.repository.SprintRepository;
import com.dm.backend.repository.StatusLogRepository;
import com.dm.backend.repository.SysUserRepository;
import com.dm.backend.repository.TaskDependencyRepository;
import com.dm.backend.repository.TaskRepository;
import com.dm.backend.repository.TeamMemberRepository;
import com.dm.backend.repository.TeamRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import javax.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 冒烟测试：8 张表的实体与 schema.sql 列映射一致，枚举/日期/金额能正常读写。
 * 需要本机 MySQL 已启动（项目后端开发前提）。
 */
@SpringBootTest
@Transactional
class PersistenceSmokeTest {

    @Autowired
    private EntityManager em;
    @Autowired
    private SysUserRepository users;
    @Autowired
    private TeamRepository teams;
    @Autowired
    private TeamMemberRepository members;
    @Autowired
    private SprintRepository sprints;
    @Autowired
    private RequirementRepository requirements;
    @Autowired
    private TaskRepository tasks;
    @Autowired
    private TaskDependencyRepository dependencies;
    @Autowired
    private StatusLogRepository logs;

    @Test
    void allEntitiesPersistAndReadBack() {
        SysUser user = new SysUser();
        user.setAccount("smoke_" + System.nanoTime());
        user.setPasswordHash("$2a$10$smokehash");
        user.setName("冒烟用户");
        users.save(user);

        Team team = new Team();
        team.setName("冒烟小队");
        team.setDescription("实体映射冒烟");
        teams.save(team);

        TeamMember member = new TeamMember();
        member.setTeamId(team.getId());
        member.setUserId(user.getId());
        member.setRole(MemberRole.PM);
        members.save(member);

        Sprint sprint = new Sprint();
        sprint.setTeamId(team.getId());
        sprint.setName("V-smoke");
        sprint.setStartDate(LocalDate.of(2026, 9, 7));
        sprint.setEndDate(LocalDate.of(2026, 9, 18));
        sprints.save(sprint);

        Requirement requirement = new Requirement();
        requirement.setTeamId(team.getId());
        requirement.setSprintId(sprint.getId());
        requirement.setTitle("冒烟需求");
        requirement.setDescription("描述");
        requirement.setType(RequirementType.FEATURE);
        requirement.setStatus(RequirementStatus.DEV);
        requirement.setEstimate(new BigDecimal("0.5"));
        requirement.setUrgent(true);
        requirement.setBlocked(true);
        requirement.setBlockedReason("等待接口");
        requirement.setBlockedAt(LocalDateTime.now());
        requirement.setAcceptanceCriteria("验收标准");
        requirement.setExternalNote("外部依赖");
        requirement.setCreatedBy(user.getId());
        requirements.save(requirement);

        Task backendTask = new Task();
        backendTask.setRequirementId(requirement.getId());
        backendTask.setType(TaskType.BACKEND);
        backendTask.setTitle("冒烟后端任务");
        backendTask.setAssigneeId(user.getId());
        backendTask.setStatus(TaskStatus.DONE);
        backendTask.setSort(0);
        backendTask.setCompletedAt(LocalDateTime.now());
        tasks.save(backendTask);

        Task frontendTask = new Task();
        frontendTask.setRequirementId(requirement.getId());
        frontendTask.setType(TaskType.FRONTEND);
        frontendTask.setTitle("冒烟前端任务");
        frontendTask.setAssigneeId(user.getId());
        frontendTask.setStatus(TaskStatus.IN_PROGRESS);
        frontendTask.setSort(1);
        frontendTask.setProgressNote("弹窗已完成");
        frontendTask.setPlannedStartDate(LocalDate.of(2026, 9, 8));
        frontendTask.setPlannedEndDate(LocalDate.of(2026, 9, 10));
        tasks.save(frontendTask);

        TaskDependency dependency = new TaskDependency();
        dependency.setTaskId(frontendTask.getId());
        dependency.setDependsOnTaskId(backendTask.getId());
        dependencies.save(dependency);

        StatusLog log = new StatusLog();
        log.setObjectType(LogObjectType.REQUIREMENT);
        log.setObjectId(requirement.getId());
        log.setFromStatus("PLANNED");
        log.setToStatus("DEV");
        log.setComment("首个任务开始");
        log.setOperatorId(user.getId());
        logs.save(log);

        assertNotNull(user.getCreatedAt(), "created_at 应由数据库默认值回填");
        assertTrue(user.getEnabled(), "enabled 默认应为 true");

        em.flush();
        em.clear();

        Requirement loaded = requirements.findById(requirement.getId())
                .orElseThrow(() -> new AssertionError("需求未能读回"));
        assertEquals("冒烟需求", loaded.getTitle());
        assertEquals(RequirementType.FEATURE, loaded.getType());
        assertEquals(RequirementStatus.DEV, loaded.getStatus());
        assertEquals(0, new BigDecimal("0.5").compareTo(loaded.getEstimate()));
        assertTrue(loaded.getUrgent());
        assertTrue(loaded.getBlocked());
        assertEquals("等待接口", loaded.getBlockedReason());
        assertEquals("验收标准", loaded.getAcceptanceCriteria());
        assertEquals("外部依赖", loaded.getExternalNote());
        assertNotNull(loaded.getUpdatedAt());

        Task loadedTask = tasks.findById(frontendTask.getId())
                .orElseThrow(() -> new AssertionError("任务未能读回"));
        assertEquals(TaskType.FRONTEND, loadedTask.getType());
        assertEquals(LocalDate.of(2026, 9, 10), loadedTask.getPlannedEndDate());
        assertEquals("弹窗已完成", loadedTask.getProgressNote());
        assertNotNull(loadedTask.getCreatedAt());

        assertEquals(1, dependencies.findAll().stream()
                .filter(d -> d.getTaskId().equals(frontendTask.getId())).count());
        assertEquals("首个任务开始", logs.findById(log.getId())
                .orElseThrow(() -> new AssertionError("日志未能读回")).getComment());
    }
}
