package com.eyemonitor.util;

import com.eyemonitor.entity.UserEntity;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 用户资料视图（profile）组装：登录/注册/资料接口统一返回结构。
 */
public final class ProfileView {

    private ProfileView() {}

    public static Map<String, Object> of(UserEntity u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("username", u.getUsername());
        m.put("nickname", u.getNickname());
        m.put("avatar", u.getAvatar()); // 相对路径 avatar/12.jpg，未设置 null
        m.put("gender", u.getGender());
        m.put("birthday", u.getBirthday() == null ? null : u.getBirthday().toString());
        m.put("bio", u.getBio());
        m.put("deviceId", u.getDeviceId());
        return m;
    }
}