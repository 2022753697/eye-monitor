package com.eyemonitor.repository;

import com.eyemonitor.entity.LocationPointEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LocationPointRepo extends JpaRepository<LocationPointEntity, Long> {

    List<LocationPointEntity> findByPairCodeAndDeviceIdAndTsBetweenOrderByTsAsc(
            String pairCode, String deviceId, long start, long end);

    void deleteByPairCode(String pairCode);

    /** 删除 ts 早于 cutoff 的记录（30 天保留清理） */
    long deleteByTsBefore(long cutoff);
}