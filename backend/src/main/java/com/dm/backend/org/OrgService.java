package com.dm.backend.org;

import com.dm.backend.auth.AuthContext;
import com.dm.backend.common.ApiException;
import com.dm.backend.common.Responses;
import com.dm.backend.entity.SysUser;
import com.dm.backend.entity.Team;
import com.dm.backend.entity.TeamMember;
import com.dm.backend.entity.enums.GlobalRole;
import com.dm.backend.entity.enums.MemberRole;
import com.dm.backend.repository.RequirementRepository;
import com.dm.backend.repository.SprintRepository;
import com.dm.backend.repository.SysUserRepository;
import com.dm.backend.repository.TeamMemberRepository;
import com.dm.backend.repository.TeamRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 组织管理：小队、成员与角色、账号。仅 ADMIN 调用（在控制器统一校验）。 */
@Service
public class OrgService {

    private static final int MIN_PASSWORD_LENGTH = 6;

    private final TeamRepository teams;
    private final TeamMemberRepository members;
    private final SysUserRepository users;
    private final SprintRepository sprints;
    private final RequirementRepository requirements;
    private final PasswordEncoder passwordEncoder;

    public OrgService(TeamRepository teams, TeamMemberRepository members, SysUserRepository users,
                      SprintRepository sprints, RequirementRepository requirements,
                      PasswordEncoder passwordEncoder) {
        this.teams = teams;
        this.members = members;
        this.users = users;
        this.sprints = sprints;
        this.requirements = requirements;
        this.passwordEncoder = passwordEncoder;
    }

    // ---------- 小队 ----------

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listTeams() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Team team : teams.findAll()) {
            long memberCount = members.findByTeamId(team.getId()).stream()
                    .map(TeamMember::getUserId).distinct().count();
            result.add(Responses.map(
                    "id", team.getId(),
                    "name", team.getName(),
                    "description", team.getDescription(),
                    "memberCount", memberCount));
        }
        return result;
    }

    @Transactional
    public Team createTeam(String name, String description) {
        if (isBlank(name)) {
            throw ApiException.badRequest("小队名称必填");
        }
        Team team = new Team();
        team.setName(name.trim());
        team.setDescription(trimToNull(description));
        return teams.save(team);
    }

    @Transactional
    public Team updateTeam(Long teamId, String name, String description) {
        Team team = requireTeam(teamId);
        if (!isBlank(name)) {
            team.setName(name.trim());
        }
        team.setDescription(trimToNull(description));
        return teams.save(team);
    }

    @Transactional
    public void deleteTeam(Long teamId) {
        requireTeam(teamId);
        if (members.existsByTeamId(teamId)) {
            throw ApiException.conflict("小队下仍有成员，请先移除成员");
        }
        if (sprints.existsByTeamId(teamId)) {
            throw ApiException.conflict("小队下仍有迭代，不能删除");
        }
        if (requirements.existsByTeamId(teamId)) {
            throw ApiException.conflict("小队下仍有需求，不能删除");
        }
        teams.deleteById(teamId);
    }

    // ---------- 成员与角色 ----------

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listMembers(Long teamId) {
        requireTeam(teamId);
        Map<Long, List<TeamMember>> byUser = members.findByTeamId(teamId).stream()
                .collect(Collectors.groupingBy(TeamMember::getUserId, LinkedHashMap::new, Collectors.toList()));
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<Long, List<TeamMember>> entry : byUser.entrySet()) {
            SysUser user = users.findById(entry.getKey()).orElse(null);
            if (user == null) {
                continue;
            }
            result.add(Responses.map(
                    "userId", user.getId(),
                    "account", user.getAccount(),
                    "name", user.getName(),
                    "enabled", user.getEnabled(),
                    "roles", entry.getValue().stream().map(TeamMember::getRole).collect(Collectors.toList())));
        }
        return result;
    }

    @Transactional
    public void addMember(Long teamId, Long userId, List<MemberRole> roles) {
        requireTeam(teamId);
        SysUser user = users.findById(userId).orElseThrow(() -> ApiException.notFound("用户不存在"));
        List<MemberRole> wanted = normalizeRoles(roles);

        List<TeamMember> existing = members.findByUserId(userId);
        // 一个用户只属于一个小队：加入本队前先移除其它小队的成员行
        existing.stream().filter(m -> !m.getTeamId().equals(teamId)).forEach(members::delete);
        Set<MemberRole> current = existing.stream()
                .filter(m -> m.getTeamId().equals(teamId))
                .map(TeamMember::getRole).collect(Collectors.toSet());
        for (MemberRole role : wanted) {
            if (!current.contains(role)) {
                TeamMember row = new TeamMember();
                row.setTeamId(teamId);
                row.setUserId(user.getId());
                row.setRole(role);
                members.save(row);
            }
        }
    }

    @Transactional
    public void updateMemberRoles(Long teamId, Long userId, List<MemberRole> roles) {
        requireTeam(teamId);
        List<TeamMember> rows = members.findByTeamIdAndUserId(teamId, userId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("该成员不在本小队");
        }
        List<MemberRole> wanted = normalizeRoles(roles);
        rows.stream().filter(row -> !wanted.contains(row.getRole())).forEach(members::delete);
        Set<MemberRole> current = rows.stream().map(TeamMember::getRole).collect(Collectors.toSet());
        for (MemberRole role : wanted) {
            if (!current.contains(role)) {
                TeamMember row = new TeamMember();
                row.setTeamId(teamId);
                row.setUserId(userId);
                row.setRole(role);
                members.save(row);
            }
        }
    }

    @Transactional
    public void removeMember(Long teamId, Long userId) {
        requireTeam(teamId);
        List<TeamMember> rows = members.findByTeamIdAndUserId(teamId, userId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("该成员不在本小队");
        }
        members.deleteAll(rows);
    }

    // ---------- 账号 ----------

    @Transactional(readOnly = true)
    public List<SysUser> listUsers() {
        return users.findAll();
    }

    @Transactional
    public SysUser createUser(String account, String name, String password, GlobalRole globalRole) {
        if (isBlank(account) || isBlank(name) || isBlank(password)) {
            throw ApiException.badRequest("账号、姓名、初始密码均必填");
        }
        String trimmedAccount = account.trim();
        if (users.existsByAccount(trimmedAccount)) {
            throw ApiException.conflict("账号已存在：" + trimmedAccount);
        }
        checkPassword(password);
        SysUser user = new SysUser();
        user.setAccount(trimmedAccount);
        user.setName(name.trim());
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setGlobalRole(globalRole == null ? GlobalRole.USER : globalRole);
        return users.save(user);
    }

    @Transactional
    public void setUserEnabled(Long userId, boolean enabled) {
        SysUser user = users.findById(userId).orElseThrow(() -> ApiException.notFound("用户不存在"));
        if (!enabled && userId.equals(AuthContext.require().getUserId())) {
            throw ApiException.badRequest("不能停用当前登录账号");
        }
        user.setEnabled(enabled);
        users.save(user);
    }

    @Transactional
    public void resetPassword(Long userId, String newPassword) {
        SysUser user = users.findById(userId).orElseThrow(() -> ApiException.notFound("用户不存在"));
        checkPassword(newPassword);
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        users.save(user);
    }

    // ---------- 内部工具 ----------

    private Team requireTeam(Long teamId) {
        return teams.findById(teamId).orElseThrow(() -> ApiException.notFound("小队不存在"));
    }

    private List<MemberRole> normalizeRoles(List<MemberRole> roles) {
        if (roles == null || roles.isEmpty()) {
            throw ApiException.badRequest("请至少选择一个队内角色");
        }
        return new ArrayList<>(new LinkedHashSet<>(roles));
    }

    private void checkPassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw ApiException.badRequest("密码长度至少 " + MIN_PASSWORD_LENGTH + " 位");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
