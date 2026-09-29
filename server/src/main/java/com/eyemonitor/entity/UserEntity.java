package com.eyemonitor.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;

/**
 * 用户表（eye_users）。
 * <p>
 * avatar: 相对路径，如 avatar/12.jpg（未上传为 null）
 * gender: female | male | null
 * ver: token 版本号，登录/登出时 +1，用于吊销旧 token 族（单设备登录）
 */
@Entity
@Table(name = "eye_users")
public class UserEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(nullable = false, length = 64)
    private String nickname;

    @Column(length = 255)
    private String avatar;

    @Column(length = 16)
    private String gender;

    private LocalDate birthday;

    @Column(length = 255)
    private String bio;

    @Column(name = "device_id", length = 64)
    private String deviceId;

    @Column(nullable = false)
    private Integer ver = 0;

    @Column(name = "created_at", nullable = false)
    private long createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }

    public String getAvatar() { return avatar; }
    public void setAvatar(String avatar) { this.avatar = avatar; }

    public String getGender() { return gender; }
    public void setGender(String gender) { this.gender = gender; }

    public LocalDate getBirthday() { return birthday; }
    public void setBirthday(LocalDate birthday) { this.birthday = birthday; }

    public String getBio() { return bio; }
    public void setBio(String bio) { this.bio = bio; }

    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }

    public Integer getVer() { return ver; }
    public void setVer(Integer ver) { this.ver = ver; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
}