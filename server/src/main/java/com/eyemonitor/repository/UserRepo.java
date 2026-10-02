package com.eyemonitor.repository;

import com.eyemonitor.entity.UserEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserRepo extends JpaRepository<UserEntity, Long> {

    UserEntity findByUsername(String username);

    boolean existsByUsername(String username);

    /**
     * H-1（修复并发竞态）：悲观写锁读取，串行化「ver/refreshTokenHash」更新。
     * refresh/login/deleteAccount 共用，消除并发 check-then-act。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from UserEntity u where u.id = :id")
    Optional<UserEntity> findByIdForUpdate(@Param("id") Long id);
}