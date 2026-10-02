package com.eyemonitor.repository;

import com.eyemonitor.entity.ChatMessageEntity;
import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ChatMessageRepo extends JpaRepository<ChatMessageEntity, Long> {

    List<ChatMessageEntity> findByPairCodeAndTsGreaterThanOrderByTsAsc(String pairCode, long afterTs);

    ChatMessageEntity findFirstByPairCodeAndTs(String pairCode, long ts);

    /** 标记已读：该配对中非 reader 发送、ts 不晚于 upToTs 且未读过的消息 */
    @Modifying
    @Transactional
    @Query("UPDATE ChatMessageEntity e SET e.readTs = :now "
            + "WHERE e.pairCode = :pairCode AND e.fromUser <> :reader "
            + "AND e.ts <= :upToTs AND e.readTs IS NULL")
    int markRead(@Param("pairCode") String pairCode,
                 @Param("reader") long reader,
                 @Param("upToTs") long upToTs,
                 @Param("now") long now);

    void deleteByPairCode(String pairCode);

    /** 删除 ts 早于 cutoff 的记录（30 天保留清理） */
    long deleteByTsBefore(long cutoff);
}