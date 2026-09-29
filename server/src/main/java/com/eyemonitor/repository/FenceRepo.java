package com.eyemonitor.repository;

import com.eyemonitor.entity.FenceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FenceRepo extends JpaRepository<FenceEntity, Long> {

    List<FenceEntity> findByPairCode(String pairCode);

    List<FenceEntity> findByOwnerUser(long userId);

    void deleteByPairCode(String pairCode);
}