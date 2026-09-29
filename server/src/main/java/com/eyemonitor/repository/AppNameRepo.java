package com.eyemonitor.repository;

import com.eyemonitor.entity.AppNameEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/** 应用名映射表仓储（eye_app_names） */
public interface AppNameRepo extends JpaRepository<AppNameEntity, String> {
}
