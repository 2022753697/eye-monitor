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

    /** 周对比统计：某用户某时间点后的文本聊天条数（非系统消息，kind=chat） */
    @Query("SELECT COUNT(e) FROM ChatMessageEntity e "
            + "WHERE e.pairCode = :pairCode AND e.fromUser = :fromUser "
            + "AND e.isSystem = false AND e.kind = 'chat' AND e.ts >= :since")
    long countChatsByUserSince(@Param("pairCode") String pairCode,
                               @Param("fromUser") long fromUser,
                               @Param("since") long since);

    /** 删除 ts 早于 cutoff 的记录（30 天保留清理） */
    long deleteByTsBefore(long cutoff);
}