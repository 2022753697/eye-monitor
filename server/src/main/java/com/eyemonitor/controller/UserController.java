package com.eyemonitor.controller;

import com.eyemonitor.entity.UserEntity;
import com.eyemonitor.model.WsMessage;
import com.eyemonitor.repository.UserRepo;
import com.eyemonitor.security.AuthUtil;
import com.eyemonitor.service.AuthService;
import com.eyemonitor.service.MediaService;
import com.eyemonitor.service.PairService;
import com.eyemonitor.util.ProfileView;
import com.eyemonitor.web.ApiResponse;
import com.eyemonitor.web.BizException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/** 用户资料与头像接口 */
@RestController
@RequestMapping("/api/user")
public class UserController {

    private final UserRepo userRepo;
    private final AuthService authService;
    private final PairService pairService;
    private final MediaService mediaService;

    public UserController(UserRepo userRepo, AuthService authService,
                          PairService pairService, MediaService mediaService) {
        this.userRepo = userRepo;
        this.authService = authService;
        this.pairService = pairService;
        this.mediaService = mediaService;
    }

    @GetMapping("/profile")
    public ApiResponse<Map<String, Object>> profile(HttpServletRequest request) {
        UserEntity u = current(request);
        return ApiResponse.ok(ProfileView.of(u));
    }

    @PutMapping("/profile")
    public ApiResponse<Map<String, Object>> updateProfile(@RequestBody Map<String, Object> body,
                                                          HttpServletRequest request) {
        UserEntity u = current(request);
        if (body.containsKey("nickname")) {
            String nickname = body.get("nickname") == null ? null : String.valueOf(body.get("nickname")).trim();
            if (nickname == null || nickname.isEmpty()) throw new BizException(400, "昵称不能为空");
            u.setNickname(nickname);
        }
        if (body.containsKey("gender")) {
            u.setGender(AuthService.normalizeGender(
                    body.get("gender") == null ? null : String.valueOf(body.get("gender"))));
        }
        if (body.containsKey("birthday")) {
            u.setBirthday(AuthService.parseDate(
                    body.get("birthday") == null ? null : String.valueOf(body.get("birthday"))));
        }
        if (body.containsKey("bio")) {
            String bio = body.get("bio") == null ? null : String.valueOf(body.get("bio")).trim();
            u.setBio(bio == null || bio.isEmpty() ? null : bio);
        }
        userRepo.save(u);
        broadcastProfile(u);
        return ApiResponse.ok(ProfileView.of(u));
    }

    @PutMapping("/avatar")
    public ApiResponse<Map<String, Object>> uploadAvatar(@RequestParam("file") MultipartFile file,
                                                         HttpServletRequest request) {
        UserEntity u = current(request);
        if (file == null || file.isEmpty()) throw new BizException(400, "文件为空");
        if (file.getSize() > MediaService.MAX_AVATAR_BYTES) throw new BizException(400, "头像不能超过 5MB");
        String ext = MediaService.extOf(file.getContentType());
        if (!"jpg".equals(ext) && !"png".equals(ext) && !"webp".equals(ext)) {
            throw new BizException(400, "头像只支持 jpg/png/webp");
        }
        // L-4（修复）：头像也走 magic-byte 校验（与媒体通道一致，防伪装内容）
        try {
            if (!MediaService.magicMatches(file.getBytes(), ext)) {
                throw new BizException(400, "文件内容与类型不符");
            }
        } catch (java.io.IOException e) {
            throw new BizException(400, "头像读取失败");
        }
        try {
            String fileName = mediaService.storeAvatar(file.getBytes(), ext, u.getId());
            // 清理旧头像（业务功能内更新；不动别人数据）
            String old = u.getAvatar();
            u.setAvatar("avatar/" + fileName);
            userRepo.save(u);
            if (old != null && old.contains("/")) {
                try {
                    Files.deleteIfExists(mediaService.resolveAvatar(old.substring(old.lastIndexOf('/') + 1)));
                } catch (Exception ignored) {}
            }
            broadcastProfile(u);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("avatar", u.getAvatar());
            return ApiResponse.ok(m);
        } catch (java.io.IOException e) {
            throw new BizException(500, "头像保存失败");
        }
    }

    @GetMapping("/avatar/{userId}")
    public ResponseEntity<Resource> avatarFile(@PathVariable Long userId) {
        UserEntity u = userRepo.findById(userId).orElseThrow(() -> new BizException(404, "用户不存在"));
        if (u.getAvatar() == null || u.getAvatar().isBlank()) throw new BizException(404, "未设置头像");
        String fileName = u.getAvatar().contains("/")
                ? u.getAvatar().substring(u.getAvatar().lastIndexOf('/') + 1) : u.getAvatar();
        Path p = mediaService.resolveAvatar(fileName);
        if (!Files.exists(p)) throw new BizException(404, "头像不存在");
        String ext = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf('.') + 1) : "jpg";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(MediaService.contentTypeOf(ext)))
                .body(new FileSystemResource(p));
    }

    /** 资料变更后广播 user_profile 给配对对方 */
    private void broadcastProfile(UserEntity u) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("nickname", u.getNickname());
        payload.put("avatar", u.getAvatar());
        payload.put("gender", u.getGender());
        payload.put("birthday", u.getBirthday() == null ? null : u.getBirthday().toString());
        payload.put("bio", u.getBio());
        String pairCode = pairService.getPairCodeOfUser(u.getId());
        WsMessage m = WsMessage.createUserProfile(null, pairCode, payload);
        pairService.forwardToPeerByUser(u.getId(), m);
    }

    private UserEntity current(HttpServletRequest request) {
        long userId = AuthUtil.currentUserId(request);
        return userRepo.findById(userId).orElseThrow(() -> new BizException(401, "用户不存在"));
    }
}