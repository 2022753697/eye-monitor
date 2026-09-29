package com.eyemonitor.repository;

import com.eyemonitor.entity.PairEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PairRepo extends JpaRepository<PairEntity, Long> {

    PairEntity findByPairCode(String pairCode);

    PairEntity findByUserA(Long userA);

    PairEntity findByUserB(Long userB);
}