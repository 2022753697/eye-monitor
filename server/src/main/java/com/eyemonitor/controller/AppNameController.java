package com.eyemonitor.controller;

import com.eyemonitor.entity.AppNameEntity;
import com.eyemonitor.repository.AppNameRepo;
import com.eyemonitor.web.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 应用名映射下发（GET /api/app-names）。
 * <p>
 * 客户端 SyncManager 同步后用于「包名 → 中文应用名」展示（如「对方打开了微信」），
 * 不再依赖客户端写死映射表。
 */
@RestController
@RequestMapping("/api/app-names")
public class AppNameController {

    private final AppNameRepo appNameRepo;

    public AppNameController(AppNameRepo appNameRepo) {
        this.appNameRepo = appNameRepo;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AppNameEntity e : appNameRepo.findAll()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("packageName", e.getPackageName());
            m.put("appName", e.getAppName());
            out.add(m);
        }
        return ApiResponse.ok(out);
    }
}
