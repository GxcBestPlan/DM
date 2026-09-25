package com.dm.backend.common;

import java.util.LinkedHashMap;
import java.util.Map;

/** 组装 JSON 响应用的小工具（JDK 8 没有 Map.of）。 */
public final class Responses {

    private Responses() {
    }

    public static Map<String, Object> map(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }
}
