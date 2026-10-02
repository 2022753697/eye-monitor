package com.eyemonitor.repository;

import com.eyemonitor.entity.SosLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SosLogRepo extends JpaRepository<SosLogEntity, Long> {

    void deleteByPairCode(String pairCode);

    /** P1-6：按时间清理（SOS 记录纳入 30 天保留窗口） */
    long deleteByTsBefore(long cutoff);
}