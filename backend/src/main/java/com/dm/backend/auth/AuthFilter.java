package com.dm.backend.auth;

import com.dm.backend.entity.SysUser;
import com.dm.backend.entity.Team;
import com.dm.backend.entity.TeamMember;
import com.dm.backend.entity.enums.MemberRole;
import com.dm.backend.repository.SysUserRepository;
import com.dm.backend.repository.TeamMemberRepository;
import com.dm.backend.repository.TeamRepository;
import io.jsonwebtoken.JwtException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 最简鉴权过滤器：/api/** 除白名单外都要求 Bearer token，
 * 校验通过后把 CurrentUser 放进 AuthContext，供控制器与 Service 使用。
 */
@Component
public class AuthFilter extends OncePerRequestFilter {

    /** 无需登录即可访问。 */
    private static final Set<String> PUBLIC_PATHS = new HashSet<>(Arrays.asList(
            "/api/auth/login",
            "/api/hello"
    ));

    private final JwtUtil jwtUtil;
    private final SysUserRepository users;
    private final TeamMemberRepository members;
    private final TeamRepository teams;

    public AuthFilter(JwtUtil jwtUtil, SysUserRepository users,
                      TeamMemberRepository members, TeamRepository teams) {
        this.jwtUtil = jwtUtil;
        this.users = users;
        this.members = members;
        this.teams = teams;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!path.startsWith("/api/") || PUBLIC_PATHS.contains(path) || "OPTIONS".equals(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            unauthorized(response, "未登录或登录已过期");
            return;
        }

        try {
            Long userId = jwtUtil.parseUserId(header.substring(7).trim());
            SysUser user = users.findById(userId).orElse(null);
            if (user == null || !Boolean.TRUE.equals(user.getEnabled())) {
                unauthorized(response, "账号不存在或已停用");
                return;
            }
            List<TeamMember> memberships = members.findByUserId(userId);
            Long teamId = memberships.isEmpty() ? null : memberships.get(0).getTeamId();
            String teamName = teamId == null ? null
                    : teams.findById(teamId).map(Team::getName).orElse(null);
            Set<MemberRole> roles = memberships.stream()
                    .map(TeamMember::getRole).collect(Collectors.toSet());

            AuthContext.set(new CurrentUser(user.getId(), user.getAccount(), user.getName(),
                    user.getGlobalRole(), teamId, teamName, roles));
            chain.doFilter(request, response);
        } catch (JwtException | IllegalArgumentException e) {
            unauthorized(response, "登录已过期，请重新登录");
        } finally {
            AuthContext.clear();
        }
    }

    private void unauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
