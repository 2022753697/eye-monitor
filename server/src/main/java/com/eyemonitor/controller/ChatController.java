package com.eyemonitor.controller;

import com.eyemonitor.entity.ChatMessageEntity;
import com.eyemonitor.repository.ChatMessageRepo;
import com.eyemonitor.security.AuthUtil;
import com.eyemonitor.service.PairService;
import com.eyemonitor.web.ApiResponse;
import com.eyemonitor.web.BizException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 聊天历史查询（换机恢复 / 增量刷新） */
@RestController
@RequestMapping("/api/chats")
public class ChatController {

    private final ChatMessageRepo chatMessageRepo;
    private final PairService pairService;

    public ChatController(ChatMessageRepo chatMessageRepo, PairService pairService) {
        this.chatMessageRepo = chatMessageRepo;
        this.pairService = pairService;
    }

    @GetMapping("/{pairCode}")
    public ApiResponse<List<Map<String, Object>>> chats(@PathVariable String pairCode,
                                                        @RequestParam(defaultValue = "0") long afterTs,
                                                        HttpServletRequest request) {
        long userId = AuthUtil.currentUserId(request);
        if (!pairService.belongsToPair(userId, pairCode)) {
            throw new BizException(403, "无权访问该配对");
        }
        List<ChatMessageEntity> rows = chatMessageRepo
                .findByPairCodeAndTsGreaterThanOrderByTsAsc(pairCode, afterTs);
        List<Map<String, Object>> out = new ArrayList<>();
        for (ChatMessageEntity e : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.getId());
            m.put("fromUser", e.getFromUser());
            m.put("text", e.getText());
            m.put("isSystem", e.isSystem());
            m.put("kind", e.getKind() == null ? "chat" : e.getKind());
            m.put("ts", e.getTs());
            out.add(m);
        }
        return ApiResponse.ok(out);
    }
}