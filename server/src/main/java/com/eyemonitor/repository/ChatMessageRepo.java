package com.eyemonitor.repository;

import com.eyemonitor.entity.ChatMessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatMessageRepo extends JpaRepository<ChatMessageEntity, Long> {

    List<ChatMessageEntity> findByPairCodeAndTsGreaterThanOrderByTsAsc(String pairCode, long afterTs);

    void deleteByPairCode(String pairCode);
}