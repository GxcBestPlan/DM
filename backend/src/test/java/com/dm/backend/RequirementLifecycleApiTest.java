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

import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 需求池 + 迭代管理 + 状态机端到端冒烟：评审规则、0.5 粒度校验、定序、排期/插队、
 * 迭代关闭只读、权限、阻塞、取消。事务内执行，数据回滚。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class RequirementLifecycleApiTest {

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
    private String pmToken;
    private String dmToken;
    private String testerToken;

    @BeforeEach
    void setUp() throws Exception {
        Team team = new Team();
        team.setName("需求测试小队-" + System.nanoTime());
        teams.save(team);
        teamId = team.getId();
        pmToken = memberWithRole("pm_", "测试PM", MemberRole.PM);
        dmToken = memberWithRole("dm_", "测试DM", MemberRole.DEV_MANAGER);
        testerToken = memberWithRole("tester_", "测试员", MemberRole.TESTER);

        Sprint sprint = new Sprint();
        sprint.setTeamId(teamId);
        sprint.setName("V-test");
        sprint.setStartDate(LocalDate.now());
        sprint.setEndDate(LocalDate.now().plusDays(13));
        sprints.save(sprint);
        sprintId = sprint.getId();
    }

    @Test
    void poolCreateReviewAndOrder() throws Exception {
        mvc.perform(get("/api/requirements")).andExpect(status().isUnauthorized());

        // 非 PM 录需求 → 403
        mvc.perform(post("/api/requirements").header("Authorization", bearer(dmToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(mapOf("title", "越权需求", "type", "FEATURE", "estimate", "1.0"))))
                .andExpect(status().isForbidden());

        // 预估人日不是 0.5 的倍数 → 400
        mvc.perform(post("/api/requirements").header("Authorization", bearer(pmToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(mapOf("title", "粒度不合法", "type", "FEATURE", "estimate", "1.3"))))
                .andExpect(status().isBadRequest());

        long reqA = createRequirement("需求A", "1.5", null);
        long reqB = createRequirement("需求B", "2.0", "验收标准B");

        mvc.perform(get("/api/requirements").header("Authorization", bearer(pmToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].poolSeq").value(1))
                .andExpect(jsonPath("$[1].poolSeq").value(2));

        // 未填验收标准就评审通过 → 400
        mvc.perform(post("/api/requirements/" + reqA + "/review").header("Authorization", bearer(pmToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(mapOf("approved", true))))
                .andExpect(status().isBadRequest());

        // 评审退回无意见 → 400；有意见 → 200 且状态不变（留痕）
        mvc.perform(post("/api/requirements/" + reqA + "/review").header("Authorization", bearer(pmToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(mapOf("approved", false))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/requirements/" + reqA + "/review").header("Authorization", bearer(pmToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(mapOf("approved", false, "comment", "请补充复现步骤"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_REVIEW"));

        // 补验收标准后评审通过 → 已评审
        mvc.perform(put("/api/requirements/" + reqA).header("Authorization", bearer(pmToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(mapOf("title", "需求A", "type", "FEATURE",
                        "estimate", "1.5", "acceptanceCriteria", "验收标准A"))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/requirements/" + reqA + "/review").header("Authorization", bearer(pmToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(mapOf("approved", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVIEWED"));

        // 详情历史里能看到评审退回意见
        mvc.perform(get("/api/requirements/" + reqA).header("Authorization", bearer(pmToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.history[*].comment").value(hasItem("评审退回：请补充复现步骤")));

        // 拖拽定序：B 排到 A 前面
        mvc.perform(put("/api/requirements/pool-order").header("Authorization", bearer(pmToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(mapOf("ids", Arrays.asList(reqB, reqA)))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/requirements").header("Authorization", bearer(pmToken)))
                .andExpect(jsonPath("$[0].id").value((int) reqB))
                .andExpect(jsonPath("$[0].poolSeq").value(1));

        // 排序列表与池不一致 → 400
        mvc.perform(put("/api/requirements/pool-order").header("Authorization", bearer(pmToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(mapOf("ids", Arrays.asList(reqA)))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void scheduleInterruptAndSprintLifecycle() throws Exception {
        long reqA = createApprovedRequirement("需求A");
        long reqB = createApprovedRequirement("需求B");

        // 非开发管理者排期 → 403
        mvc.perform(post("/api/requirements/" + reqA + "/schedule").header("Authorization", bearer(pmToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(mapOf("sprintId", sprintId, "urgent", false))))
                .andExpect(status().isForbidden());

        // 排入规划中的迭代 → 已排期
        schedule(reqA, false)
                .andExpect(jsonPath("$.status").value("PLANNED"));

        // 迭代未开始却要插队 → 400
        mvc.perform(post("/api/requirements/" + reqB + "/schedule").header("Authorization", bearer(dmToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(mapOf("sprintId", sprintId, "urgent", true))))
                .andExpect(status().isBadRequest());

        // 迭代未开始时移出 → 回到已评审，且排到池尾
        mvc.perform(post("/api/requirements/" + reqA + "/unschedule").header("Authorization", bearer(dmToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVIEWED"));

        schedule(reqA, false);
        schedule(reqB, false);

        // 开始迭代
        mvc.perform(post("/api/sprints/" + sprintId + "/start").header("Authorization", bearer(dmToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // 已有进行中迭代时不能再建迭代
        mvc.perform(post("/api/sprints").header("Authorization", bearer(dmToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(mapOf("name", "V-second", "startDate", LocalDate.now().toString(),
                        "endDate", LocalDate.now().plusDays(13).toString()))))
                .andExpect(status().isConflict());

        // 迭代进行中新增需求：普通排入 → 400；插队 → 成功且带 urgent 标记
        long reqC = createApprovedRequirement("需求C");
        mvc.perform(post("/api/requirements/" + reqC + "/schedule").header("Authorization", bearer(dmToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(mapOf("sprintId", sprintId, "urgent", false))))
                .andExpect(status().isBadRequest());
        schedule(reqC, true)
                .andExpect(jsonPath("$.status").value("PLANNED"))
                .andExpect(jsonPath("$.urgent").value(true));

        // 关闭迭代 → 需求只读
        mvc.perform(post("/api/sprints/" + sprintId + "/close").header("Authorization", bearer(dmToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));
        mvc.perform(post("/api/requirements/" + reqA + "/dev").header("Authorization", bearer(dmToken)))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/requirements/" + reqA + "/block").header("Authorization", bearer(dmToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(mapOf("reason", "关闭后不应允许"))))
                .andExpect(status().isConflict());

        // 重新打开后可继续推进
        mvc.perform(post("/api/sprints/" + sprintId + "/reopen").header("Authorization", bearer(dmToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        mvc.perform(post("/api/requirements/" + reqA + "/dev").header("Authorization", bearer(dmToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DEV"));
    }

    @Test
    void devToPublishFlowBlockingAndCancel() throws Exception {
        long reqId = createApprovedRequirement("需求D");
        schedule(reqId, false);
        mvc.perform(post("/api/sprints/" + sprintId + "/start").header("Authorization", bearer(dmToken)))
                .andExpect(status().isOk());

        // 权限：PM 不能推进开发；测试不能领取未就绪的需求
        mvc.perform(post("/api/requirements/" + reqId + "/dev").header("Authorization", bearer(pmToken)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/requirements/" + reqId + "/claim").header("Authorization", bearer(testerToken)))
                .andExpect(status().isConflict());

        mvc.perform(post("/api/requirements/" + reqId + "/dev").header("Authorization", bearer(dmToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DEV"));

        // 阻塞覆盖层：无原因 400 → 挂阻塞 → 解除
        mvc.perform(post("/api/requirements/" + reqId + "/block").header("Authorization", bearer(dmToken))
                .contentType(MediaType.APPLICATION_JSON).content(json(mapOf("reason", ""))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/requirements/" + reqId + "/block").header("Authorization", bearer(dmToken))
                .contentType(MediaType.APPLICATION_JSON).content(json(mapOf("reason", "等待接口"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blocked").value(true));
        mvc.perform(post("/api/requirements/" + reqId + "/unblock").header("Authorization", bearer(dmToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blocked").value(false));

        // 跳状态：DEV 直接验收 → 409
        mvc.perform(post("/api/requirements/" + reqId + "/accept").header("Authorization", bearer(testerToken)))
                .andExpect(status().isConflict());

        mvc.perform(post("/api/requirements/" + reqId + "/ready-for-test").header("Authorization", bearer(dmToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY_FOR_TEST"));

        // 测试领取
        mvc.perform(post("/api/requirements/" + reqId + "/claim").header("Authorization", bearer(testerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TESTING"));

        // 退回必须填原因；退回后回到开发中
        mvc.perform(post("/api/requirements/" + reqId + "/reject").header("Authorization", bearer(testerToken))
                .contentType(MediaType.APPLICATION_JSON).content(json(mapOf("comment", ""))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/requirements/" + reqId + "/reject").header("Authorization", bearer(testerToken))
                .contentType(MediaType.APPLICATION_JSON).content(json(mapOf("comment", "首屏超标"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DEV"));

        // 再走一遍到已验收 → 发布由开发管理者手动标记
        mvc.perform(post("/api/requirements/" + reqId + "/ready-for-test").header("Authorization", bearer(dmToken)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/requirements/" + reqId + "/claim").header("Authorization", bearer(testerToken)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/requirements/" + reqId + "/accept").header("Authorization", bearer(testerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
        mvc.perform(post("/api/requirements/" + reqId + "/publish").header("Authorization", bearer(pmToken)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/requirements/" + reqId + "/publish").header("Authorization", bearer(dmToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));

        // 终态不可再变更
        mvc.perform(post("/api/requirements/" + reqId + "/cancel").header("Authorization", bearer(dmToken)))
                .andExpect(status().isConflict());

        // 池内需求由 PM 取消，取消后从列表消失
        long reqE = createRequirement("待取消需求", "1.0", null);
        mvc.perform(post("/api/requirements/" + reqE + "/cancel").header("Authorization", bearer(pmToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        MvcResult poolList = mvc.perform(get("/api/requirements").header("Authorization", bearer(pmToken)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode pool = om.readTree(poolList.getResponse().getContentAsString());
        for (JsonNode item : pool) {
            assertNotEquals(reqE, item.get("id").asLong(), "已取消需求不应出现在需求池列表");
        }
    }

    // ---------- 工具 ----------

    private String memberWithRole(String prefix, String name, MemberRole role) throws Exception {
        String account = prefix + System.nanoTime();
        String password = "pw123456";
        SysUser user = new SysUser();
        user.setAccount(account);
        user.setName(name);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setGlobalRole(GlobalRole.USER);
        users.save(user);

        TeamMember member = new TeamMember();
        member.setTeamId(teamId);
        member.setUserId(user.getId());
        member.setRole(role);
        members.save(member);
        return login(account, password);
    }

    private String login(String account, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json(mapOf("account", account, "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        return om.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    private long createRequirement(String title, String estimate, String acceptanceCriteria) throws Exception {
        MvcResult result = mvc.perform(post("/api/requirements").header("Authorization", bearer(pmToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(mapOf("title", title, "type", "FEATURE", "estimate", estimate,
                        "acceptanceCriteria", acceptanceCriteria))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_REVIEW"))
                .andReturn();
        return idOf(result);
    }

    private long createApprovedRequirement(String title) throws Exception {
        long id = createRequirement(title + "-" + System.nanoTime(), "1.5", "验收标准：" + title);
        mvc.perform(post("/api/requirements/" + id + "/review").header("Authorization", bearer(pmToken))
                .contentType(MediaType.APPLICATION_JSON).content(json(mapOf("approved", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVIEWED"));
        return id;
    }

    private org.springframework.test.web.servlet.ResultActions schedule(long requirementId, boolean urgent)
            throws Exception {
        return mvc.perform(post("/api/requirements/" + requirementId + "/schedule")
                .header("Authorization", bearer(dmToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(mapOf("sprintId", sprintId, "urgent", urgent))))
                .andExpect(status().isOk());
    }

    private long idOf(MvcResult result) throws Exception {
        JsonNode node = om.readTree(result.getResponse().getContentAsString());
        return node.get("id").asLong();
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
