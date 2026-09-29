package com.eyemonitor.repository;

import com.eyemonitor.entity.ChatMessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatMessageRepo extends JpaRepository<ChatMessageEntity, Long> {

    List<ChatMessageEntity> findByPairCodeAndTsGreaterThanOrderByTsAsc(String pairCode, long afterTs);

    void deleteByPairCode(String pairCode);

    /** 删除 ts 早于 cutoff 的记录（30 天保留清理） */
    long deleteByTsBefore(long cutoff);
}