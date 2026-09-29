package com.eyemonitor.repository;

import com.eyemonitor.entity.FolderEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FolderRepo extends JpaRepository<FolderEntity, Long> {

    List<FolderEntity> findByPairCode(String pairCode);
}