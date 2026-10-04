package com.eyemonitor.repository;

import com.eyemonitor.entity.AffectionStateEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 亲密度状态仓储（pairCode 主键，pair 唯一一行，共享池）。
 */
public interface AffectionStateRepo extends JpaRepository<AffectionStateEntity, String> {
}
