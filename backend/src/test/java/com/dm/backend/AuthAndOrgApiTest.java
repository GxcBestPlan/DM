package com.dm.backend;

import com.dm.backend.entity.SysUser;
import com.dm.backend.entity.enums.GlobalRole;
import com.dm.backend.repository.SysUserRepository;
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

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 鉴权 + 组织管理端到端冒烟：登录、401/403、小队、成员多角色、账号停用后 token 立即失效。
 * 需要本机 MySQL 已启动；测试在事务内执行，数据回滚。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuthAndOrgApiTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper om;
    @Autowired
    private SysUserRepository users;
    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void ensureAdmin() {
        SysUser admin = users.findByAccount("admin").orElseGet(SysUser::new);
        admin.setAccount("admin");
        admin.setName("系统管理员");
        admin.setGlobalRole(GlobalRole.ADMIN);
        admin.setEnabled(true);
        admin.setPasswordHash(passwordEncoder.encode("admin123"));
        users.save(admin);
    }

    @Test
    void authAndOrgFlow() throws Exception {
        // 未带 token 访问受保护接口 → 401
        mvc.perform(get("/api/org/users")).andExpect(status().isUnauthorized());

        // 密码错误 → 401，且不泄露账号是否存在
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json(mapOf("account", "admin", "password", "wrong-password"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("账号或密码错误"));

        String adminToken = tokenOf(login("admin", "admin123"));

        // 建小队
        long teamId = idOf(mvc.perform(post("/api/org/teams")
                .header("Authorization", bearer(adminToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(mapOf("name", "冒烟小队-" + System.nanoTime(), "description", "测试"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").isNotEmpty())
                .andReturn());

        // 建账号
        String pmAccount = "pm_" + System.nanoTime();
        long pmUserId = idOf(mvc.perform(post("/api/org/users")
                .header("Authorization", bearer(adminToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(mapOf("account", pmAccount, "name", "测试PM",
                        "password", "pm123456", "globalRole", "USER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andReturn());

        // 加入小队并授予 PM 角色
        mvc.perform(post("/api/org/teams/" + teamId + "/members")
                .header("Authorization", bearer(adminToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(mapOf("userId", pmUserId, "roles", Arrays.asList("PM")))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/org/teams/" + teamId + "/members")
                .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].account").value(pmAccount))
                .andExpect(jsonPath("$[0].roles[0]").value("PM"));

        // 角色改为 PM + TESTER（多角色）
        mvc.perform(put("/api/org/teams/" + teamId + "/members/" + pmUserId)
                .header("Authorization", bearer(adminToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(mapOf("roles", Arrays.asList("PM", "TESTER")))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/org/teams/" + teamId + "/members")
                .header("Authorization", bearer(adminToken)))
                .andExpect(jsonPath("$[0].roles.length()").value(2));

        // 非管理员访问组织管理 → 403
        String pmToken = tokenOf(login(pmAccount, "pm123456"));
        mvc.perform(get("/api/org/users").header("Authorization", bearer(pmToken)))
                .andExpect(status().isForbidden());

        // 但本队成员可以读成员名单（排期选人需要）
        mvc.perform(get("/api/org/teams/" + teamId + "/members").header("Authorization", bearer(pmToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].account").value(pmAccount));

        // /api/auth/me 返回所属小队与角色
        mvc.perform(get("/api/auth/me").header("Authorization", bearer(pmToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.teamId").value((int) teamId))
                .andExpect(jsonPath("$.roles.length()").value(2));

        // 管理员不能停用自己
        long adminId = idOf(mvc.perform(get("/api/auth/me").header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk()).andReturn());
        mvc.perform(put("/api/org/users/" + adminId + "/status")
                .header("Authorization", bearer(adminToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(mapOf("enabled", false))))
                .andExpect(status().isBadRequest());

        // 停用成员后，其 token 立即失效 → 401
        mvc.perform(put("/api/org/users/" + pmUserId + "/status")
                .header("Authorization", bearer(adminToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(mapOf("enabled", false))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").header("Authorization", bearer(pmToken)))
                .andExpect(status().isUnauthorized());

        // 移除成员
        mvc.perform(delete("/api/org/teams/" + teamId + "/members/" + pmUserId)
                .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/org/teams/" + teamId + "/members")
                .header("Authorization", bearer(adminToken)))
                .andExpect(jsonPath("$.length()").value(0));
    }

    // ---------- 工具 ----------

    private MvcResult login(String account, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json(mapOf("account", account, "password", password))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andReturn();
    }

    private String tokenOf(MvcResult result) throws Exception {
        return om.readTree(result.getResponse().getContentAsByteArray()).get("token").asText();
    }

    private long idOf(MvcResult result) throws Exception {
        JsonNode node = om.readTree(result.getResponse().getContentAsByteArray());
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
