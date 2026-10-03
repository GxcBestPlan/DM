package com.dm.backend.org;

import com.dm.backend.auth.AuthContext;
import com.dm.backend.entity.SysUser;
import com.dm.backend.entity.Team;
import com.dm.backend.entity.enums.GlobalRole;
import com.dm.backend.entity.enums.MemberRole;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 组织管理接口（§9.10）：小队、成员与角色、账号。 */
@RestController
@RequestMapping("/api/org")
public class OrgController {

    private final OrgService orgService;

    public OrgController(OrgService orgService) {
        this.orgService = orgService;
    }

    // ---------- 小队 ----------

    @GetMapping("/teams")
    public List<Map<String, Object>> listTeams() {
        adminOnly();
        return orgService.listTeams();
    }

    @PostMapping("/teams")
    public Team createTeam(@RequestBody TeamRequest request) {
        adminOnly();
        return orgService.createTeam(request.getName(), request.getDescription());
    }

    @PutMapping("/teams/{teamId}")
    public Team updateTeam(@PathVariable Long teamId, @RequestBody TeamRequest request) {
        adminOnly();
        return orgService.updateTeam(teamId, request.getName(), request.getDescription());
    }

    @DeleteMapping("/teams/{teamId}")
    public void deleteTeam(@PathVariable Long teamId) {
        adminOnly();
        orgService.deleteTeam(teamId);
    }

    // ---------- 成员与角色 ----------

    /** 队内成员名单：排期选人需要，本队成员均可读；组织配置仍限 ADMIN。 */
    @GetMapping("/teams/{teamId}/members")
    public List<Map<String, Object>> listMembers(@PathVariable Long teamId) {
        AuthContext.require().requireTeamAccess(teamId);
        return orgService.listMembers(teamId);
    }

    @PostMapping("/teams/{teamId}/members")
    public void addMember(@PathVariable Long teamId, @RequestBody MemberRequest request) {
        adminOnly();
        orgService.addMember(teamId, request.getUserId(), request.getRoles());
    }

    @PutMapping("/teams/{teamId}/members/{userId}")
    public void updateMemberRoles(@PathVariable Long teamId, @PathVariable Long userId,
                                  @RequestBody MemberRequest request) {
        adminOnly();
        orgService.updateMemberRoles(teamId, userId, request.getRoles());
    }

    @DeleteMapping("/teams/{teamId}/members/{userId}")
    public void removeMember(@PathVariable Long teamId, @PathVariable Long userId) {
        adminOnly();
        orgService.removeMember(teamId, userId);
    }

    // ---------- 账号 ----------

    @GetMapping("/users")
    public List<SysUser> listUsers() {
        adminOnly();
        return orgService.listUsers();
    }

    @PostMapping("/users")
    public SysUser createUser(@RequestBody UserRequest request) {
        adminOnly();
        return orgService.createUser(request.getAccount(), request.getName(),
                request.getPassword(), request.getGlobalRole());
    }

    @PutMapping("/users/{userId}/status")
    public void setUserEnabled(@PathVariable Long userId, @RequestBody StatusRequest request) {
        adminOnly();
        orgService.setUserEnabled(userId, Boolean.TRUE.equals(request.getEnabled()));
    }

    @PostMapping("/users/{userId}/password")
    public void resetPassword(@PathVariable Long userId, @RequestBody PasswordRequest request) {
        adminOnly();
        orgService.resetPassword(userId, request.getNewPassword());
    }

    private void adminOnly() {
        AuthContext.require().requireAdmin();
    }

    // ---------- 请求体 ----------

    public static class TeamRequest {
        private String name;
        private String description;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }
    }

    public static class MemberRequest {
        private Long userId;
        private List<MemberRole> roles;

        public Long getUserId() {
            return userId;
        }

        public void setUserId(Long userId) {
            this.userId = userId;
        }

        public List<MemberRole> getRoles() {
            return roles;
        }

        public void setRoles(List<MemberRole> roles) {
            this.roles = roles;
        }
    }

    public static class UserRequest {
        private String account;
        private String name;
        private String password;
        private GlobalRole globalRole;

        public String getAccount() {
            return account;
        }

        public void setAccount(String account) {
            this.account = account;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public GlobalRole getGlobalRole() {
            return globalRole;
        }

        public void setGlobalRole(GlobalRole globalRole) {
            this.globalRole = globalRole;
        }
    }

    public static class StatusRequest {
        private Boolean enabled;

        public Boolean getEnabled() {
            return enabled;
        }

        public void setEnabled(Boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class PasswordRequest {
        private String newPassword;

        public String getNewPassword() {
            return newPassword;
        }

        public void setNewPassword(String newPassword) {
            this.newPassword = newPassword;
        }
    }
}
