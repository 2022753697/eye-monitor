package com.eyemonitor.controller;

import com.eyemonitor.entity.AnniversaryEntity;
import com.eyemonitor.model.WsMessage;
import com.eyemonitor.repository.AnniversaryRepo;
import com.eyemonitor.security.AuthUtil;
import com.eyemonitor.service.AuthService;
import com.eyemonitor.service.PairService;
import com.eyemonitor.web.ApiResponse;
import com.eyemonitor.web.BizException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 纪念日 CRUD（变更后向配对两设备广播 anniversary_sync） */
@RestController
@RequestMapping("/api/anniversaries")
public class AnniversaryController {

    private final AnniversaryRepo anniversaryRepo;
    private final PairService pairService;

    public AnniversaryController(AnniversaryRepo anniversaryRepo, PairService pairService) {
        this.anniversaryRepo = anniversaryRepo;
        this.pairService = pairService;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(HttpServletRequest request) {
        String pairCode = requirePairCode(request);
        // 客户端 SyncManager 按 data.anniversaries 数组读取（与 fence/chats 同步契约一致）
        List<Map<String, Object>> out = new ArrayList<>();
        for (AnniversaryEntity e : anniversaryRepo.findByPairCodeOrderByUpdatedAtAsc(pairCode)) {
            out.add(view(e));
        }
        return ApiResponse.ok(Map.of("anniversaries", out));
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> create(@RequestBody Map<String, Object> body,
                                                   HttpServletRequest request) {
        String pairCode = requirePairCode(request);
        String name = body.get("name") == null ? null : String.valueOf(body.get("name")).trim();
        if (name == null || name.isEmpty()) throw new BizException(400, "名称不能为空");
        java.time.LocalDate date = AuthService.parseDate(
                body.get("date") == null ? null : String.valueOf(body.get("date")));
        if (date == null) throw new BizException(400, "日期必填");
        boolean repeat = Boolean.TRUE.equals(body.get("repeat"));

        AnniversaryEntity e = new AnniversaryEntity();
        e.setPairCode(pairCode);
        e.setName(name);
        e.setDate(date);
        e.setRepeat(repeat);
        e.setUpdatedAt(System.currentTimeMillis());
        anniversaryRepo.save(e);

        pairService.broadcastToPair(pairCode, WsMessage.createAnniversarySync(
                null, pairCode, "add", e.getId(), e.getName(), e.getDate().toString(),
                e.isRepeat(), e.getUpdatedAt()));
        return ApiResponse.ok(view(e));
    }

    @PutMapping("/{id}")
    public ApiResponse<Map<String, Object>> update(@PathVariable Long id,
                                                   @RequestBody Map<String, Object> body,
                                                   HttpServletRequest request) {
        String pairCode = requirePairCode(request);
        AnniversaryEntity e = stdGet(id, pairCode);
        if (body.containsKey("name")) {
            String name = body.get("name") == null ? null : String.valueOf(body.get("name")).trim();
            if (name == null || name.isEmpty()) throw new BizException(400, "名称不能为空");
            e.setName(name);
        }
        if (body.containsKey("date")) {
            java.time.LocalDate date = AuthService.parseDate(
                    body.get("date") == null ? null : String.valueOf(body.get("date")));
            if (date == null) throw new BizException(400, "日期格式应为 yyyy-MM-dd");
            e.setDate(date);
        }
        if (body.containsKey("repeat")) {
            e.setRepeat(Boolean.TRUE.equals(body.get("repeat")));
        }
        e.setUpdatedAt(System.currentTimeMillis());
        anniversaryRepo.save(e);

        pairService.broadcastToPair(pairCode, WsMessage.createAnniversarySync(
                null, pairCode, "update", e.getId(), e.getName(), e.getDate().toString(),
                e.isRepeat(), e.getUpdatedAt()));
        return ApiResponse.ok(view(e));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id, HttpServletRequest request) {
        String pairCode = requirePairCode(request);
        AnniversaryEntity e = stdGet(id, pairCode);
        anniversaryRepo.delete(e);
        pairService.broadcastToPair(pairCode, WsMessage.createAnniversarySync(
                null, pairCode, "delete", e.getId(), null, null, false, e.getUpdatedAt()));
        return ApiResponse.ok(null);
    }

    private AnniversaryEntity stdGet(long id, String pairCode) {
        AnniversaryEntity e = anniversaryRepo.findById(id)
                .orElseThrow(() -> new BizException(404, "纪念日不存在"));
        if (!pairCode.equals(e.getPairCode())) throw new BizException(403, "无权操作该纪念日");
        return e;
    }

    private String requirePairCode(HttpServletRequest request) {
        long userId = AuthUtil.currentUserId(request);
        String pairCode = pairService.getPairCodeOfUser(userId);
        if (pairCode == null) throw new BizException(403, "未配对");
        return pairCode;
    }

    private Map<String, Object> view(AnniversaryEntity e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("name", e.getName());
        m.put("date", e.getDate().toString());
        m.put("repeat", e.isRepeat());
        m.put("updatedAt", e.getUpdatedAt());
        return m;
    }
}