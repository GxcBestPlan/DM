package com.dm.backend.auth;

import com.dm.backend.common.ApiException;
import com.dm.backend.entity.enums.GlobalRole;
import com.dm.backend.entity.enums.MemberRole;

import java.util.Collections;
import java.util.Set;

/** 当前登录用户：账号信息 + 所属小队 + 队内多角色。 */
public class CurrentUser {

    private final Long userId;
    private final String account;
    private final String name;
    private final GlobalRole globalRole;
    private final Long teamId;
    private final String teamName;
    private final Set<MemberRole> roles;

    public CurrentUser(Long userId, String account, String name, GlobalRole globalRole,
                       Long teamId, String teamName, Set<MemberRole> roles) {
        this.userId = userId;
        this.account = account;
        this.name = name;
        this.globalRole = globalRole;
        this.teamId = teamId;
        this.teamName = teamName;
        this.roles = roles == null ? Collections.emptySet() : roles;
    }

    public Long getUserId() {
        return userId;
    }

    public String getAccount() {
        return account;
    }

    public String getName() {
        return name;
    }

    public GlobalRole getGlobalRole() {
        return globalRole;
    }

    public Long getTeamId() {
        return teamId;
    }

    public String getTeamName() {
        return teamName;
    }

    public Set<MemberRole> getRoles() {
        return roles;
    }

    public boolean isAdmin() {
        return globalRole == GlobalRole.ADMIN;
    }

    public void requireAdmin() {
        if (!isAdmin()) {
            throw ApiException.forbidden("需要系统管理员权限");
        }
    }
}
