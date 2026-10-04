package com.eyemonitor.repository;

import com.eyemonitor.entity.AffectionEventLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 好感度积分流水仓储（幂等流水 + 每日上限聚合）。
 */
public interface AffectionEventLogRepo extends JpaRepository<AffectionEventLogEntity, Long> {

    /** 幂等判定：dedupKey 已存在则跳过计分 */
    boolean existsByDedupKey(String dedupKey);

    /** 当日 (pairCode, 本地日期) 区间积分聚合（每日 50 上限截断依据） */
    @Query("SELECT COALESCE(SUM(e.points), 0) FROM AffectionEventLogEntity e "
            + "WHERE e.pairCode = :pairCode AND e.ts >= :start AND e.ts < :end")
    long sumPointsBetween(@Param("pairCode") String pairCode,
                          @Param("start") long start,
                          @Param("end") long end);

    /** 某来源某时间点后的流水（周对比统计 check_in 明细） */
    List<AffectionEventLogEntity> findByPairCodeAndSourceAndTsGreaterThanEqual(
            String pairCode, String source, long since);

    /** 某来源某时间点后的条数（周对比统计 online 档数） */
    long countByPairCodeAndSourceAndTsGreaterThanEqual(String pairCode, String source, long since);
}
