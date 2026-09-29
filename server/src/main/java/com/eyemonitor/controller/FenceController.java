package com.eyemonitor.controller;

import com.eyemonitor.entity.FenceEntity;
import com.eyemonitor.model.WsMessage;
import com.eyemonitor.repository.FenceRepo;
import com.eyemonitor.security.AuthUtil;
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

/** 围栏 CRUD（配置落库并同步双方，判定在客户端） */
@RestController
@RequestMapping("/api/fences")
public class FenceController {

    private final FenceRepo fenceRepo;
    private final PairService pairService;

    public FenceController(FenceRepo fenceRepo, PairService pairService) {
        this.fenceRepo = fenceRepo;
        this.pairService = pairService;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(HttpServletRequest request) {
        long userId = AuthUtil.currentUserId(request);
        String pairCode = requirePairCode(request);
        // 自愈迁移：本人名下残留旧配对码的围栏（服务器重启换码后遗留）自动归到当前配对，
        // 避免出现“自己删除自己的围栏却被判无权”的新旧码不一致
        for (FenceEntity e : fenceRepo.findByOwnerUser(userId)) {
            if (!pairCode.equals(e.getPairCode())) {
                e.setPairCode(pairCode);
                fenceRepo.save(e);
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        java.util.LinkedHashMap<Long, FenceEntity> merged = new java.util.LinkedHashMap<>();
        for (FenceEntity e : fenceRepo.findByPairCode(pairCode)) {
            merged.put(e.getId(), e);
        }
        for (FenceEntity e : fenceRepo.findByOwnerUser(userId)) {
            merged.putIfAbsent(e.getId(), e);
        }
        for (FenceEntity e : merged.values()) {
            out.add(view(e));
        }
        return ApiResponse.ok(out);
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> create(@RequestBody Map<String, Object> body,
                                                   HttpServletRequest request) {
        String pairCode = requirePairCode(request);
        long userId = AuthUtil.currentUserId(request);
        String name = body.get("name") == null ? null : String.valueOf(body.get("name")).trim();
        if (name == null || name.isEmpty()) throw new BizException(400, "围栏名称不能为空");
        double lat = num(body.get("lat"));
        double lng = num(body.get("lng"));
        double radius = num(body.get("radius"));
        if (radius <= 0) throw new BizException(400, "半径必须大于 0");

        FenceEntity e = new FenceEntity();
        e.setPairCode(pairCode);
        e.setOwnerUser(userId);
        e.setName(name);
        e.setCenterLat(lat);
        e.setCenterLng(lng);
        e.setRadius(radius);
        e.setEnabled(true);
        e.setCreatedAt(System.currentTimeMillis());
        fenceRepo.save(e);

        broadcast(e, "upsert", pairCode);
        return ApiResponse.ok(view(e));
    }

    @PutMapping("/{id}")
    public ApiResponse<Map<String, Object>> update(@PathVariable Long id,
                                                   @RequestBody Map<String, Object> body,
                                                   HttpServletRequest request) {
        String pairCode = requirePairCode(request);
        FenceEntity e = stdGet(id, pairCode, request);
        if (body.containsKey("name")) {
            String name = body.get("name") == null ? null : String.valueOf(body.get("name")).trim();
            if (name == null || name.isEmpty()) throw new BizException(400, "围栏名称不能为空");
            e.setName(name);
        }
        if (body.containsKey("lat")) e.setCenterLat(num(body.get("lat")));
        if (body.containsKey("lng")) e.setCenterLng(num(body.get("lng")));
        if (body.containsKey("radius")) {
            double r = num(body.get("radius"));
            if (r <= 0) throw new BizException(400, "半径必须大于 0");
            e.setRadius(r);
        }
        if (body.containsKey("enabled")) e.setEnabled(Boolean.TRUE.equals(body.get("enabled")));
        fenceRepo.save(e);

        broadcast(e, "upsert", pairCode);
        return ApiResponse.ok(view(e));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id, HttpServletRequest request) {
        String pairCode = requirePairCode(request);
        FenceEntity e = stdGet(id, pairCode, request);
        fenceRepo.delete(e);
        broadcast(e, "delete", pairCode);
        return ApiResponse.ok(null);
    }

    private void broadcast(FenceEntity e, String action, String pairCode) {
        pairService.broadcastToPair(pairCode, WsMessage.createFenceSync(
                null, pairCode, action, e.getId(), e.getName(),
                e.getCenterLat(), e.getCenterLng(), e.getRadius(), e.isEnabled()));
    }

    private FenceEntity stdGet(long id, String pairCode, HttpServletRequest request) {
        FenceEntity e = fenceRepo.findById(id).orElseThrow(() -> new BizException(404, "围栏不存在"));
        long userId = AuthUtil.currentUserId(request);
        // 判权：当前配对码匹配，或本人创建（即使配对码因重启/重配对变化也能操作自己的围栏）
        if (!pairCode.equals(e.getPairCode()) && e.getOwnerUser() != userId) {
            throw new BizException(403, "无权操作该围栏");
        }
        return e;
    }

    private String requirePairCode(HttpServletRequest request) {
        long userId = AuthUtil.currentUserId(request);
        String pairCode = pairService.getPairCodeOfUser(userId);
        if (pairCode == null) throw new BizException(403, "未配对");
        return pairCode;
    }

    private double num(Object o) {
        if (o instanceof Number) return ((Number) o).doubleValue();
        try {
            return Double.parseDouble(String.valueOf(o));
        } catch (Exception e) {
            return 0d;
        }
    }

    private Map<String, Object> view(FenceEntity e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("name", e.getName());
        m.put("lat", e.getCenterLat());
        m.put("lng", e.getCenterLng());
        m.put("radius", e.getRadius());
        m.put("enabled", e.isEnabled());
        return m;
    }
}