package com.eyemonitor.controller;

import com.eyemonitor.entity.FenceEntity;
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

/**
 * 围栏 CRUD（围栏为创建者私有：A 建的围栏只 A 可见/可管理，删除只影响 A；
 * 监控判定在客户端本机完成——本机用自己围栏 + 对端位置做进出判断）。
 * 不做 WS 广播（fence_sync 已废弃），客户端以 REST 结果维护本地 Room 缓存。
 */
@RestController
@RequestMapping("/api/fences")
public class FenceController {

    private final FenceRepo fenceRepo;
    private final PairService pairService;

    public FenceController(FenceRepo fenceRepo, PairService pairService) {
        this.fenceRepo = fenceRepo;
        this.pairService = pairService;
    }

    /** 仅返回本人创建的围栏（私有），与配对共享无关 */
    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(HttpServletRequest request) {
        long userId = AuthUtil.currentUserId(request);
        List<Map<String, Object>> out = new ArrayList<>();
        for (FenceEntity e : fenceRepo.findByOwnerUserOrderByIdAsc(userId)) {
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
        return ApiResponse.ok(view(e));
    }

    @PutMapping("/{id}")
    public ApiResponse<Map<String, Object>> update(@PathVariable Long id,
                                                   @RequestBody Map<String, Object> body,
                                                   HttpServletRequest request) {
        FenceEntity e = stdGetOwned(id, request);
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
        return ApiResponse.ok(view(e));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id, HttpServletRequest request) {
        FenceEntity e = stdGetOwned(id, request);
        fenceRepo.delete(e);
        return ApiResponse.ok(null);
    }

    /** 判权：仅创建者本人可操作自己的围栏（严格私有，不共享） */
    private FenceEntity stdGetOwned(long id, HttpServletRequest request) {
        FenceEntity e = fenceRepo.findById(id).orElseThrow(() -> new BizException(404, "围栏不存在"));
        long userId = AuthUtil.currentUserId(request);
        if (e.getOwnerUser() != userId) {
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