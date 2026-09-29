package com.eyemonitor.repository;

import com.eyemonitor.entity.AnniversaryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AnniversaryRepo extends JpaRepository<AnniversaryEntity, Long> {

    List<AnniversaryEntity> findByPairCodeOrderByUpdatedAtAsc(String pairCode);

    void deleteByPairCode(String pairCode);
}