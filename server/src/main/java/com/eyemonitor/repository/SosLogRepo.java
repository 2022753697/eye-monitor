package com.eyemonitor.repository;

import com.eyemonitor.entity.SosLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SosLogRepo extends JpaRepository<SosLogEntity, Long> {

    void deleteByPairCode(String pairCode);
}