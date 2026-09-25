package com.dm.backend;

import com.dm.backend.entity.Requirement;
import com.dm.backend.entity.Sprint;
import com.dm.backend.entity.StatusLog;
import com.dm.backend.entity.SysUser;
import com.dm.backend.entity.Task;
import com.dm.backend.entity.Team;
import com.dm.backend.entity.TeamMember;
import com.dm.backend.entity.enums.GlobalRole;
import com.dm.backend.entity.enums.LogObjectType;
import com.dm.backend.entity.enums.MemberRole;
import com.dm.backend.entity.enums.RequirementStatus;
import com.dm.backend.entity.enums.RequirementType;
import com.dm.backend.entity.enums.SprintStatus;
import com.dm.backend.entity.enums.TaskStatus;
import com.dm.backend.entity.enums.TaskType;
import com.dm.backend.repository.RequirementRepository;
import com.dm.backend.repository.SprintRepository;
import com.dm.backend.repository.StatusLogRepository;
import com.dm.backend.repository.SysUserRepository;
import com.dm.backend.repository.TaskRepository;
import com.dm.backend.repository.TeamMemberRepository;
import com.dm.backend.repository.TeamRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 跨队总览 + 迭代报表 + 跨队汇总 + 测试队列（跨迭代查询）端到端冒烟。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class OverviewAndReportApiTest {

    private static final String PASSWORD = "pw123456";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper om;
    @Autowired
    private TeamRepository teams;
    @Autowired
    private SysUserRepository users;
    @Autowired
    private TeamMemberRepository members;
    @Autowired
    private SprintRepository sprints;
    @Autowired
    private RequirementRepository requirements;
    @Autowired
    private TaskRepository tasks;
    @Autowired
    private StatusLogRepository logs;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private Long teamAId;
    private Long teamBId;
    private Long sprintId;
    private String pmToken;
    private String dmToken;
    private String supervisorToken;

    @BeforeEach
    void setUp() throws Exception {
        teamAId = createTeam("交易研发一队").getId();
        teamBId = createTeam("基础平台队").getId();

        SysUser pm = createUser("pm_", "产品甲", GlobalRole.USER, teamAId, MemberRole.PM);
        SysUser dm = createUser("dm_", "管理甲", GlobalRole.USER, teamAId, MemberRole.DEV_MANAGER);
        SysUser dev1 = createUser("fe_", "前端甲", GlobalRole.USER, teamAId, MemberRole.FRONTEND_DEV);
        SysUser dev2 = createUser("be_", "后端乙", GlobalRole.USER, teamAId, MemberRole.BACKEND_DEV);
        SysUser supervisor = createUser("sup_", "陈总", GlobalRole.SUPERVISOR, null, null);
        pmToken = login(pm.getAccount());
        dmToken = login(dm.getAccount());
        supervisorToken = login(supervisor.getAccount());

        Sprint sprint = new Sprint();
        sprint.setTeamId(teamAId);
        sprint.setName("V-report");
        sprint.setStartDate(LocalDate.now());
        sprint.setEndDate(LocalDate.now().plusDays(13));
        sprint.setStatus(SprintStatus.ACTIVE);
        sprints.save(sprint);
        sprintId = sprint.getId();

        Requirement published = requirement("已完成需求", RequirementStatus.PUBLISHED, "1.0", false, false, pm.getId());
        Requirement blocked = requirement("阻塞需求", RequirementStatus.DEV, "2.0", true, true, pm.getId());
        Requirement ready = requirement("待测试需求", RequirementStatus.READY_FOR_TEST, "1.5", false, false, pm.getId());
        requirement("已排期需求", RequirementStatus.PLANNED, "1.0", false, false, pm.getId());

        StatusLog log = new StatusLog();
        log.setObjectType(LogObjectType.REQUIREMENT);
        log.setObjectId(published.getId());
        log.setFromStatus(RequirementStatus.ACCEPTED.name());
        log.setToStatus(RequirementStatus.PUBLISHED.name());
        log.setComment("标记已发布");
        log.setOperatorId(dm.getId());
        logs.save(log);

        task(published, dev1.getId());
        task(ready, dev1.getId());
        task(blocked, dev2.getId());
        assertTrue(Boolean.TRUE.equals(blocked.getBlocked()));
    }

    @Test
    void overviewAndSprintReport() throws Exception {
        // 权限：本队 PM 不能看跨队总览 / 跨队汇总
        mvc.perform(get("/api/overview").header("Authorization", bearer(pmToken)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/reports/teams").header("Authorization", bearer(pmToken)))
                .andExpect(status().isForbidden());

        // 跨队总览：supervisor 只读可见全部小队
        MvcResult overview = mvc.perform(get("/api/overview").header("Authorization", bearer(supervisorToken)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode teamA = findTeam(overview, teamAId);
        assertEquals(4, teamA.get("requirementCount").asInt());
        assertEquals(1, teamA.get("doneCount").asInt());
        assertEquals(25.0, teamA.get("completionRate").asDouble(), 0.01);
        assertEquals(1, teamA.get("blockedCount").asInt());
        assertTrue(teamA.get("riskCount").asInt() >= 1, "阻塞需求应计入延期高风险");
        assertTrue(teamA.get("loadPercent").asDouble() > 0, "有排期需求时应计算负载");
        assertEquals(2, teamA.get("devCount").asInt());

        JsonNode teamB = findTeam(overview, teamBId);
        assertTrue(teamB.get("sprintId").isNull(), "无迭代小队返回空迭代信息");
        assertEquals(0, teamB.get("requirementCount").asInt());

        // 迭代报告：本队 DM 可看
        MvcResult report = mvc.perform(get("/api/reports/sprints/" + sprintId)
                .header("Authorization", bearer(dmToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requirementCount").value(4))
                .andExpect(jsonPath("$.doneCount").value(1))
                .andExpect(jsonPath("$.completionRate").value(25.0))
                .andExpect(jsonPath("$.onTimeRate").value(100.0))
                .andExpect(jsonPath("$.urgentCount").value(1))
                .andExpect(jsonPath("$.urgentRate").value(25.0))
                .andExpect(jsonPath("$.statusDistribution.length()").value(4))
                .andExpect(jsonPath("$.memberLoad.length()").value(2))
                .andExpect(jsonPath("$.dailyTrend.length()").value(14))
                .andReturn();
        JsonNode reportNode = om.readTree(report.getResponse().getContentAsByteArray());
        assertTrue(reportNode.get("blockedDays").asDouble() >= 0.9, "阻塞时长应约 1 天");
        assertEquals(1, reportNode.get("dailyTrend").get(13).get("doneCount").asInt(),
                "趋势末日累计完成数应等于完成需求数");
        assertEquals(devEffort(reportNode), 2.5, 0.01, "人均负载按承担需求的预估人日合计（1.0 + 1.5）");

        // 跨队汇总：完成率对比 + 延期风险 Top
        MvcResult comparison = mvc.perform(get("/api/reports/teams")
                .header("Authorization", bearer(supervisorToken)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode teamARow = findTeam(comparison, teamAId);
        assertEquals(25.0, teamARow.get("completionRate").asDouble(), 0.01);
        assertTrue(teamARow.get("riskTop").size() >= 1);
        assertEquals("阻塞需求", teamARow.get("riskTop").get(0).get("title").asText().split("-")[0]);
    }

    @Test
    void testQueueAcrossSprints() throws Exception {
        // 测试工作台：跨迭代按状态取本队全部需求
        mvc.perform(get("/api/requirements?all=true&status=READY_FOR_TEST")
                .header("Authorization", bearer(pmToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value(org.hamcrest.Matchers.startsWith("待测试需求")));

        // 需求池语义不受 all 影响
        mvc.perform(get("/api/requirements").header("Authorization", bearer(pmToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // ---------- 工具 ----------

    private double devEffort(JsonNode reportNode) {
        for (JsonNode member : reportNode.get("memberLoad")) {
            if ("前端甲".equals(member.get("name").asText())) {
                return member.get("effortDays").asDouble();
            }
        }
        return -1;
    }

    private JsonNode findTeam(MvcResult result, Long teamId) throws Exception {
        JsonNode array = om.readTree(result.getResponse().getContentAsByteArray());
        for (JsonNode node : array) {
            if (node.get("teamId").asLong() == teamId) {
                return node;
            }
        }
        throw new AssertionError("总览中未找到小队 " + teamId);
    }

    private Team createTeam(String name) {
        Team team = new Team();
        team.setName(name + "-" + System.nanoTime());
        return teams.save(team);
    }

    private SysUser createUser(String prefix, String name, GlobalRole globalRole,
                               Long teamId, MemberRole role) {
        SysUser user = new SysUser();
        user.setAccount(prefix + System.nanoTime());
        user.setName(name);
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setGlobalRole(globalRole);
        users.save(user);
        if (teamId != null && role != null) {
            TeamMember member = new TeamMember();
            member.setTeamId(teamId);
            member.setUserId(user.getId());
            member.setRole(role);
            members.save(member);
        }
        return user;
    }

    private Requirement requirement(String title, RequirementStatus status, String estimate,
                                    boolean urgent, boolean blocked, Long createdBy) {
        Requirement requirement = new Requirement();
        requirement.setTeamId(teamAId);
        requirement.setSprintId(sprintId);
        requirement.setTitle(title + "-" + System.nanoTime());
        requirement.setType(RequirementType.FEATURE);
        requirement.setStatus(status);
        requirement.setEstimate(new BigDecimal(estimate));
        requirement.setUrgent(urgent);
        requirement.setCreatedBy(createdBy);
        requirement.setBlocked(blocked);
        if (blocked) {
            requirement.setBlockedReason("等待外部依赖");
            requirement.setBlockedAt(LocalDateTime.now().minusHours(24));
        }
        return requirements.save(requirement);
    }

    private void task(Requirement requirement, Long assigneeId) {
        Task task = new Task();
        task.setRequirementId(requirement.getId());
        task.setType(TaskType.BACKEND);
        task.setTitle("任务-" + requirement.getTitle());
        task.setAssigneeId(assigneeId);
        task.setStatus(TaskStatus.TODO);
        tasks.save(task);
    }

    private String login(String account) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json(mapOf("account", account, "password", PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();
        return om.readTree(result.getResponse().getContentAsByteArray()).get("token").asText();
    }

    private String json(Map<String, Object> body) throws Exception {
        return om.writeValueAsString(body);
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private static Map<String, Object> mapOf(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }
}
