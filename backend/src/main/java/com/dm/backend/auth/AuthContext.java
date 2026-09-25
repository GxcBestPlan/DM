package com.dm.backend.auth;

import com.dm.backend.common.ApiException;

/** 请求级当前用户存放（由 AuthFilter 写入并清理）。 */
public final class AuthContext {

    private static final ThreadLocal<CurrentUser> HOLDER = new ThreadLocal<>();

    private AuthContext() {
    }

    public static void set(CurrentUser user) {
        HOLDER.set(user);
    }

    public static void clear() {
        HOLDER.remove();
    }

    public static CurrentUser get() {
        return HOLDER.get();
    }

    public static CurrentUser require() {
        CurrentUser user = HOLDER.get();
        if (user == null) {
            throw ApiException.unauthorized("未登录或登录已过期");
        }
        return user;
    }
}
