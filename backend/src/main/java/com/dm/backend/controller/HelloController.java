package com.dm.backend.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 演示前后端分离的示例接口。
 */
@RestController
@RequestMapping("/api")
public class HelloController {

    @GetMapping("/hello")
    public Map<String, Object> hello() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("message", "Hello from DM backend!");
        data.put("backend", "Spring Boot 2.7.18");
        data.put("java", "JDK 8");
        return data;
    }
}
