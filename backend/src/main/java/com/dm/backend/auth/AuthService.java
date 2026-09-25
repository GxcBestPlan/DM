package com.dm.backend.auth;

import com.dm.backend.common.ApiException;
import com.dm.backend.common.Responses;
import com.dm.backend.entity.SysUser;
import com.dm.backend.entity.Team;
import com.dm.backend.entity.TeamMember;
import com.dm.backend.entity.enums.MemberRole;
import com.dm.backend.repository.SysUserRepository;
import com.dm.backend.repository.TeamMemberRepository;
import com.dm.backend.repository.TeamRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class AuthService {

    private final SysUserRepository users;
    private final TeamMemberRepository members;
    private final TeamRepository teams;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    public AuthService(SysUserRepository users, TeamMemberRepository members, TeamRepository teams,
                       PasswordEncoder passwordEncoder, JwtUtil jwtUtil) {
        this.users = users;
        this.members = members;
        this.teams = teams;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> login(String account, String password) {
        if (account == null || account.trim().isEmpty() || password == null || password.isEmpty()) {
            throw ApiException.badRequest("请输入账号和密码");
        }
        SysUser user = users.findByAccount(account.trim())
                .orElseThrow(() -> ApiException.unauthorized("账号或密码错误"));
        if (!Boolean.TRUE.equals(user.getEnabled())) {
            throw ApiException.unauthorized("账号已停用，请联系管理员");
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw ApiException.unauthorized("账号或密码错误");
        }
        return Responses.map(
                "token", jwtUtil.createToken(user.getId()),
                "user", profile(loadCurrentUser(user)));
    }

    /** 由数据库记录组装当前用户视图（含小队与队内角色）。 */
    public CurrentUser loadCurrentUser(SysUser user) {
        List<TeamMember> memberships = members.findByUserId(user.getId());
        Long teamId = memberships.isEmpty() ? null : memberships.get(0).getTeamId();
        String teamName = teamId == null ? null : teams.findById(teamId).map(Team::getName).orElse(null);
        Set<MemberRole> roles = memberships.stream().map(TeamMember::getRole).collect(Collectors.toSet());
        return new CurrentUser(user.getId(), user.getAccount(), user.getName(),
                user.getGlobalRole(), teamId, teamName, roles);
    }

    public Map<String, Object> profile(CurrentUser user) {
        return Responses.map(
                "id", user.getUserId(),
                "account", user.getAccount(),
                "name", user.getName(),
                "globalRole", user.getGlobalRole(),
                "teamId", user.getTeamId(),
                "teamName", user.getTeamName(),
                "roles", user.getRoles());
    }
}
