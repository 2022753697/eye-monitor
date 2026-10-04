package com.eyemonitor.controller;

import com.eyemonitor.security.AuthUtil;
import com.eyemonitor.service.AffectionService;
import com.eyemonitor.service.PairService;
import com.eyemonitor.web.ApiResponse;
import com.eyemonitor.web.BizException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 好感度/等级查询（App 打开/进聊天页时兜底拉取；WS affection_sync 为主通道）。
 * <p>
 * 响应：{points, level, progress, title, weekStats:{chats:{me,peer}, checkIns:{me,peer}, onlineMinutes}}。
 * 共享性：points/level/progress/title 是 pair 唯一共享值，双端永远一致。
 * JWT 鉴权由 AuthInterceptor 统一处理（/api/** 除 /api/auth/**）。
 */
@RestController
@RequestMapping("/api/affection")
public class AffectionController {

    private final AffectionService affectionService;
    private final PairService pairService;

    public AffectionController(AffectionService affectionService, PairService pairService) {
        this.affectionService = affectionService;
        this.pairService = pairService;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> get(HttpServletRequest request) {
        long userId = AuthUtil.currentUserId(request);
        String pairCode = pairService.getPairCodeOfUser(userId);
        if (pairCode == null) throw new BizException(403, "未配对");
        AffectionService.LevelInfo info = affectionService.currentLevel(pairCode);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("points", info.points);
        body.put("level", info.level);
        body.put("progress", info.progress);
        body.put("title", info.title);
        body.put("weekStats", affectionService.weekStats(pairCode, userId));
        return ApiResponse.ok(body);
    }
}
