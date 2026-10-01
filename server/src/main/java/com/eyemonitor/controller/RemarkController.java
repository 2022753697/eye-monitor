package com.eyemonitor.controller;

import com.eyemonitor.entity.PairEntity;
import com.eyemonitor.entity.RemarkEntity;
import com.eyemonitor.repository.PairRepo;
import com.eyemonitor.repository.RemarkRepo;
import com.eyemonitor.security.AuthUtil;
import com.eyemonitor.web.ApiResponse;
import com.eyemonitor.web.BizException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 备注（私有视角，换机跟随账号）。
 * <p>
 * 备注 = 我(userId)对我配对对象(peerUserId)的称呼，仅我可见/可取，
 * 不推送给对方设备。peerUserId 由服务端从 pair 解析，客户端无需知道。
 * <p>
 * - PUT /api/remark { remark }  保存我的备注（空串=清除）
 * - GET /api/remark             取回我的备注（换机/重装恢复用）
 */
@RestController
@RequestMapping("/api/remark")
public class RemarkController {

    private final RemarkRepo remarkRepo;
    private final PairRepo pairRepo;

    public RemarkController(RemarkRepo remarkRepo, PairRepo pairRepo) {
        this.remarkRepo = remarkRepo;
        this.pairRepo = pairRepo;
    }

    @PutMapping
    public ApiResponse<Map<String, Object>> save(@RequestBody Map<String, Object> body,
                                                 HttpServletRequest request) {
        long userId = AuthUtil.currentUserId(request);
        if (userId <= 0) throw new BizException(401, "未登录");
        Long peer = peerOf(userId);
        if (peer == null) throw new BizException(400, "未配对");

        String remark = body.get("remark") == null
                ? null : String.valueOf(body.get("remark")).trim();
        RemarkEntity e = remarkRepo.findByUserIdAndPeerUserId(userId, peer);
        if (remark == null || remark.isEmpty()) {
            if (e != null) remarkRepo.delete(e);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("cleared", true);
            return ApiResponse.ok(out);
        }
        if (e == null) {
            e = new RemarkEntity();
            e.setUserId(userId);
            e.setPeerUserId(peer);
        }
        e.setRemark(remark);
        e.setUpdatedAt(System.currentTimeMillis());
        remarkRepo.save(e);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("saved", true);
        out.put("remark", remark);
        return ApiResponse.ok(out);
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> get(HttpServletRequest request) {
        long userId = AuthUtil.currentUserId(request);
        if (userId <= 0) throw new BizException(401, "未登录");
        Long peer = peerOf(userId);
        if (peer == null) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("remark", null);
            return ApiResponse.ok(out);
        }
        RemarkEntity e = remarkRepo.findByUserIdAndPeerUserId(userId, peer);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("remark", e != null ? e.getRemark() : null);
        return ApiResponse.ok(out);
    }

    /** 我(userId)的配对对象 userId；未配对返回 null */
    private Long peerOf(long userId) {
        PairEntity p = pairRepo.findByUserA(userId);
        if (p == null) p = pairRepo.findByUserB(userId);
        if (p == null) return null;
        return p.getUserA() == userId ? p.getUserB() : p.getUserA();
    }
}