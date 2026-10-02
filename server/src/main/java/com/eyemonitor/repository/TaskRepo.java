package com.eyemonitor.repository;

import com.eyemonitor.entity.TaskEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TaskRepo extends JpaRepository<TaskEntity, Long> {

    TaskEntity findByTaskId(String taskId);

    List<TaskEntity> findByPairCodeOrderByTsDesc(String pairCode);

    /** 解除配对时清理（PairController.unpair 同模式） */
    void deleteByPairCode(String pairCode);
}
