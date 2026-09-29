package com.eyemonitor.controller;

import com.eyemonitor.entity.LocationPointEntity;
import com.eyemonitor.repository.LocationPointRepo;
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

/** 轨迹查询：对方轨迹回放数据源 */
@RestController
@RequestMapping("/api/tracks")
public class TrackController {

    private static final int MAX_POINTS = 5000;

    private final LocationPointRepo locationPointRepo;
    private final PairService pairService;

    public TrackController(LocationPointRepo locationPointRepo, PairService pairService) {
        this.locationPointRepo = locationPointRepo;
        this.pairService = pairService;
    }

    @GetMapping("/{pairCode}")
    public ApiResponse<List<Map<String, Object>>> tracks(@PathVariable String pairCode,
                                                         @RequestParam long start,
                                                         @RequestParam long end,
                                                         @RequestParam String device,
                                                         HttpServletRequest request) {
        long userId = AuthUtil.currentUserId(request);
        if (!pairService.belongsToPair(userId, pairCode)) {
            throw new BizException(403, "无权访问该配对");
        }
        List<LocationPointEntity> points = locationPointRepo
                .findByPairCodeAndDeviceIdAndTsBetweenOrderByTsAsc(pairCode, device, start, end);
        List<Map<String, Object>> out = new ArrayList<>();
        for (LocationPointEntity p : points) {
            if (out.size() >= MAX_POINTS) break;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("lat", p.getLat());
            m.put("lng", p.getLng());
            m.put("accuracy", p.getAccuracy());
            m.put("ts", p.getTs());
            out.add(m);
        }
        return ApiResponse.ok(out);
    }
}