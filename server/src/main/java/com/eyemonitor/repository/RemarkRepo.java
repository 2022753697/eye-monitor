package com.eyemonitor.repository;

import com.eyemonitor.entity.RemarkEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/** 备注表仓库 */
public interface RemarkRepo extends JpaRepository<RemarkEntity, Long> {

    /** 我(userId)对配对对象(peerUserId)的备注（每对唯一） */
    RemarkEntity findByUserIdAndPeerUserId(Long userId, Long peerUserId);
}