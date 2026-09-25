package com.dm.backend;

import com.dm.backend.entity.Sprint;
import com.dm.backend.entity.SysUser;
import com.dm.backend.entity.Team;
import com.dm.backend.entity.TeamMember;
import com.dm.backend.entity.enums.GlobalRole;
import com.dm.backend.entity.enums.MemberRole;
import com.dm.backend.repository.SprintRepository;
import com.dm.backend.repository.SysUserRepository;
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

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 任务拆解 / 依赖规则 / 自动流转（D1、D2）/ 我的任务 / 管道冲突 端到端冒烟。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class TaskPipelineApiTest {

    private static final String PASSWORD = "pw123456";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper om;
    @Autowired
    private SysUserRepository users;
    @Autowired
    private TeamRepository teams;
    @Autowired
    private TeamMemberRepository members;
    @Autowired
    private SprintRepository sprints;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private Long teamId;
    private Long sprintId;
    private Long dev1Id;
    private Long dev2Id;
    private String pmToken;
    private String dmToken;
    private String dev1Token;
    private String dev2Token;

    @BeforeEach
    void setUp() throws Exception {
        Team team = new Team();
        team.setName("任务测试小队-" + System.nanoTime());
        teams.save(team);
        teamId = team.getId();

        SysUser pm = createMember("pm_", "测试PM", MemberRole.PM);
        SysUser dm = createMember("dm_", "测试DM", MemberRole.DEV_MANAGER);
        SysUser dev1 = createMember("fe_", "前端甲", MemberRole.FRONTEND_DEV);
        SysUser dev2 = createMember("be_", "后端乙", MemberRole.BACKEND_DEV);
        dev1Id = dev1.getId();
        dev2Id = dev2.getId();
        pmToken = login(pm.getAccount());
        dmToken = login(dm.getAccount());
        dev1Token = login(dev1.getAccount());
        dev2Token = login(dev2.getAccount());

        Sprint sprint = new Sprint();
        sprint.setTeamId(teamId);
        sprint.setName("V-task");
        sprint.setStartDate(LocalDate.now());
        sprint.setEndDate(LocalDate.now().plusDays(13));
        sprints.save(sprint);
        sprintId = sprint.getId();
    }

    @Test
    void taskBreakdownAndDependencyRules() throws Exception {
        // 池内需求不能拆任务 → 409
        long poolRequirement = createApprovedRequirement("池内需求");
        mvc.perform(post("/api/requirements/" + poolRequirement + "/tasks").header("Authorization", bearer(dmToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(mapOf("type", "BACKEND", "title", "池内任务"))))
                .andExpect(status().isConflict());

        long requirementId = createScheduledRequirement("需求T");
        // PM 拆任务 → 403
        mvc.perform(post("/api/requirements/" + requirementId + "/tasks").header("Authorization", bearer(pmToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(mapOf("type", "BACKEND", "title", "越权任务"))))
                .andExpect(status().isForbidden());

        long backendTask = createTask(requirementId, "BACKEND", "后端接口", dev2Id);
        long frontendTask = createTask(requirementId, "FRONTEND", "前端页面", dev1Id);

        // 依赖：前端依赖后端 → OK
        mvc.perform(post("/api/tasks/" + frontendTask + "/dependencies").header("Authorization", bearer(dmToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(mapOf("dependsOnTaskId", backendTask))))
                .andExpect(status().isOk());
        // 重复依赖 → 409
        mvc.perform(post("/api/tasks/" + frontendTask + "/dependencies").header("Authorization", bearer(dmToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(mapOf("dependsOnTaskId", backendTask))))
                .andExpect(status().isConflict());
        // 后端依赖前端 → 400
        mvc.perform(post("/api/tasks/" + backendTask + "/dependencies").header("Authorization", bearer(dmToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(mapOf("dependsOnTaskId", frontendTask))))
                .andExpect(status().isBadRequest());
        // 自依赖 → 400
        mvc.perform(post("/api/tasks/" + frontendTask + "/dependencies").header("Authorization", bearer(dmToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(mapOf("dependsOnTaskId", frontendTask))))
                .andExpect(status().isBadRequest());

        // 跨需求依赖 → 400
        long otherRequirement = createScheduledRequirement("需求T2");
        long otherBackendTask = createTask(otherRequirement, "BACKEND", "另一个后端", dev2Id);
        mvc.perform(post("/api/tasks/" + frontendTask + "/dependencies").header("Authorization", bearer(dmToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(mapOf("dependsOnTaskId", otherBackendTask))))
                .andExpect(status().isBadRequest());

        // 需求详情里能看到依赖关系
        MvcResult detail = mvc.perform(get("/api/requirements/" + requirementId).header("Authorization", bearer(dmToken)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode tasks = om.readTree(detail.getResponse().getContentAsByteArray()).get("tasks");
        JsonNode frontendNode = null;
        for (JsonNode node : tasks) {
            if (node.get("id").asLong() == frontendTask) {
                frontendNode = node;
            }
        }
        assertEquals(backendTask, frontendNode.get("dependsOnTaskIds").get(0).asLong());

        // 移除依赖后可再加回，说明移除有效
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/api/tasks/" + frontendTask + "/dependencies/" + backendTask)
                .header("Authorization", bearer(dmToken)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/tasks/" + frontendTask + "/dependencies").header("Authorization", bearer(dmToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(mapOf("dependsOnTaskId", backendTask))))
                .andExpect(status().isOk());
    }

    @Test
    void taskStatusFlowAutoTransitionsAndMine() throws Exception {
        long requirementId = createScheduledRequirement("需求S");
        long backendTask = createTask(requirementId, "BACKEND", "后端接口", dev2Id);
        long frontendTask = createTask(requirementId, "FRONTEND", "前端页面", dev1Id);
        mvc.perform(post("/api/tasks/" + frontendTask + "/dependencies").header("Authorization", bearer(dmToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(mapOf("dependsOnTaskId", backendTask))))
                .andExpect(status().isOk());

        // 迭代未开始不能开始任务
        mvc.perform(post("/api/tasks/" + backendTask + "/start").header("Authorization", bearer(dev2Token)))
                .andExpect(status().isConflict());

        startSprint();
        // 非负责人不能操作他人任务
        mvc.perform(post("/api/tasks/" + backendTask + "/start").header("Authorization", bearer(dev1Token)))
                .andExpect(status().isForbidden());

        // D1：首个任务开始 → 需求自动从"已排期"转"开发中"
        mvc.perform(post("/api/tasks/" + backendTask + "/start").header("Authorization", bearer(dev2Token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.status").value("IN_PROGRESS"));
        mvc.perform(get("/api/requirements/" + requirementId).header("Authorization", bearer(dmToken)))
                .andExpect(jsonPath("$.status").value("DEV"));

        // 进展备注
        mvc.perform(post("/api/tasks/" + backendTask + "/progress").header("Authorization", bearer(dev2Token))
                .contentType(MediaType.APPLICATION_JSON).content(json(mapOf("note", "接口已完成 80%"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.progressNote").value("接口已完成 80%"));

        // 阻塞：原因必填
        mvc.perform(post("/api/tasks/" + backendTask + "/block").header("Authorization", bearer(dev2Token))
                .contentType(MediaType.APPLICATION_JSON).content(json(mapOf("reason", ""))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/tasks/" + backendTask + "/block").header("Authorization", bearer(dev2Token))
                .contentType(MediaType.APPLICATION_JSON).content(json(mapOf("reason", "等待联调环境"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blocked").value(true));
        mvc.perform(post("/api/tasks/" + backendTask + "/unblock").header("Authorization", bearer(dev2Token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blocked").value(false));

        // 前端任务开始并完成：后端未完成 → 完成成功但给出依赖提示，需求仍在开发中
        mvc.perform(post("/api/tasks/" + frontendTask + "/start").header("Authorization", bearer(dev1Token)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/tasks/" + frontendTask + "/complete").header("Authorization", bearer(dev1Token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.warning").isNotEmpty());
        mvc.perform(get("/api/requirements/" + requirementId).header("Authorization", bearer(dmToken)))
                .andExpect(jsonPath("$.status").value("DEV"));

        // 我的任务（今日待办）包含进行中的后端任务
        MvcResult mine = mvc.perform(get("/api/tasks/mine?scope=today").header("Authorization", bearer(dev2Token)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode mineNodes = om.readTree(mine.getResponse().getContentAsByteArray());
        assertTrue(mineNodes.size() >= 1,
                "今日待办应包含进行中的任务，实际返回：" + mine.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertTrue(mineNodes.get(0).get("requirementTitle").asText().startsWith("需求S"),
                "第一条应为需求S的任务，实际返回：" + mine.getResponse().getContentAsString(StandardCharsets.UTF_8));

        // D2：最后一个任务完成 → 需求自动转待测试
        mvc.perform(post("/api/tasks/" + backendTask + "/complete").header("Authorization", bearer(dev2Token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.warning").doesNotExist());
        mvc.perform(get("/api/requirements/" + requirementId).header("Authorization", bearer(dmToken)))
                .andExpect(jsonPath("$.status").value("READY_FOR_TEST"));
    }

    @Test
    void pipelineScheduleAndConflicts() throws Exception {
        long requirementId = createScheduledRequirement("需求P");
        long task1 = createTask(requirementId, "BACKEND", "后端接口P", dev2Id);
        long task2 = createTask(requirementId, "FRONTEND", "前端页面P", dev1Id);

        // 未排期：两个任务都在 unplanned
        JsonNode pipeline = getPipeline();
        assertEquals(2, pipeline.get("unplanned").size());

        LocalDate day1 = LocalDate.now();
        LocalDate day2 = LocalDate.now().plusDays(1);
        LocalDate day3 = LocalDate.now().plusDays(2);

        // 同一个人两个任务日期重叠 → 冲突
        scheduleTask(task1, dev2Id, day1, day3);
        scheduleTask(task2, dev2Id, day2, day3);
        pipeline = getPipeline();
        assertEquals(0, pipeline.get("unplanned").size());
        assertTrue(pipeline.get("conflicts").size() > 0, "同日重叠应被识别为冲突");
        assertEquals(dev2Id.longValue(), pipeline.get("conflicts").get(0).get("userId").asLong());

        // 改派给另一个人 → 冲突消失
        scheduleTask(task2, dev1Id, day2, day3);
        pipeline = getPipeline();
        assertEquals(0, pipeline.get("conflicts").size());

        // 日期超出迭代范围 → 400
        mvc.perform(put("/api/tasks/" + task1 + "/schedule").header("Authorization", bearer(dmToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(mapOf("assigneeId", dev2Id,
                        "plannedStartDate", LocalDate.now().plusDays(20).toString(),
                        "plannedEndDate", LocalDate.now().plusDays(21).toString()))))
                .andExpect(status().isBadRequest());

        // 负责人不在本小队 → 400
        SysUser outsider = new SysUser();
        outsider.setAccount("out_" + System.nanoTime());
        outsider.setName("外部人员");
        outsider.setPasswordHash(passwordEncoder.encode(PASSWORD));
        outsider.setGlobalRole(GlobalRole.USER);
        users.save(outsider);
        mvc.perform(put("/api/tasks/" + task1 + "/schedule").header("Authorization", bearer(dmToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(mapOf("assigneeId", outsider.getId(),
                        "plannedStartDate", day1.toString(), "plannedEndDate", day2.toString()))))
                .andExpect(status().isBadRequest());
    }

    // ---------- 工具 ----------

    private JsonNode getPipeline() throws Exception {
        MvcResult result = mvc.perform(get("/api/sprints/" + sprintId + "/pipeline")
                .header("Authorization", bearer(dmToken)))
                .andExpect(status().isOk())
                .andReturn();
        return om.readTree(result.getResponse().getContentAsByteArray());
    }

    private void scheduleTask(long taskId, Long assigneeId, LocalDate start, LocalDate end) throws Exception {
        mvc.perform(put("/api/tasks/" + taskId + "/schedule").header("Authorization", bearer(dmToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(mapOf("assigneeId", assigneeId,
                        "plannedStartDate", start.toString(), "plannedEndDate", end.toString()))))
                .andExpect(status().isOk());
    }

    private void startSprint() throws Exception {
        mvc.perform(post("/api/sprints/" + sprintId + "/start").header("Authorization", bearer(dmToken)))
                .andExpect(status().isOk());
    }

    private long createTask(long requirementId, String type, String title, Long assigneeId) throws Exception {
        MvcResult result = mvc.perform(post("/api/requirements/" + requirementId + "/tasks")
                .header("Authorization", bearer(dmToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(mapOf("type", type, "title", title, "assigneeId", assigneeId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TODO"))
                .andReturn();
        return om.readTree(result.getResponse().getContentAsByteArray()).get("id").asLong();
    }

    private long createApprovedRequirement(String title) throws Exception {
        MvcResult created = mvc.perform(post("/api/requirements").header("Authorization", bearer(pmToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(mapOf("title", title + "-" + System.nanoTime(), "type", "FEATURE",
                        "estimate", "1.5", "acceptanceCriteria", "验收标准"))))
                .andExpect(status().isOk())
                .andReturn();
        long id = om.readTree(created.getResponse().getContentAsByteArray()).get("id").asLong();
        mvc.perform(post("/api/requirements/" + id + "/review").header("Authorization", bearer(pmToken))
                .contentType(MediaType.APPLICATION_JSON).content(json(mapOf("approved", true))))
                .andExpect(status().isOk());
        return id;
    }

    private long createScheduledRequirement(String title) throws Exception {
        long id = createApprovedRequirement(title);
        mvc.perform(post("/api/requirements/" + id + "/schedule").header("Authorization", bearer(dmToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(mapOf("sprintId", sprintId, "urgent", false))))
                .andExpect(status().isOk());
        return id;
    }

    private SysUser createMember(String prefix, String name, MemberRole role) {
        SysUser user = new SysUser();
        user.setAccount(prefix + System.nanoTime());
        user.setName(name);
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setGlobalRole(GlobalRole.USER);
        users.save(user);

        TeamMember member = new TeamMember();
        member.setTeamId(teamId);
        member.setUserId(user.getId());
        member.setRole(role);
        members.save(member);
        return user;
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
