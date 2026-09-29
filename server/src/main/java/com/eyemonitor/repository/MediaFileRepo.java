package com.eyemonitor.repository;

import com.eyemonitor.entity.MediaFileEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MediaFileRepo extends JpaRepository<MediaFileEntity, String> {

    List<MediaFileEntity> findByPairCode(String pairCode);

    List<MediaFileEntity> findByPairCodeAndFolderId(String pairCode, Long folderId);

    List<MediaFileEntity> findByFolderId(Long folderId);

    void deleteByPairCode(String pairCode);
}