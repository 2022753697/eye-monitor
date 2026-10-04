package com.eyemonitor.service;

import com.eyemonitor.entity.PairEntity;
import com.eyemonitor.repository.PairRepo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ⑦ 启动时清理：过期 PENDING（30 分钟未完成）加载时回收，未过期 PENDING 与 COMPLETE 配对保留。
 * <p>
 * 纯逻辑注入式：PairRepo 全 Mockito mock，不依赖真库。
 */
class PairCleanupTest {

    private static PairEntity entity(String code, Long userA, Long userB, String status, long createdAt) {
        PairEntity e = new PairEntity();
        e.setPairCode(code);
        e.setUserA(userA);
        e.setUserB(userB);
        e.setStatus(status);
        e.setCreatedAt(createdAt);
        return e;
    }

    @Test
    void loadFromDb_purgesExpiredPending_keepsActivePairs() {
        PairRepo repo = mock(PairRepo.class);
        long now = System.currentTimeMillis();
        long expiredTs = now - 31 * 60_000L;      // 31 分钟前：过期
        long freshTs = now - 5 * 60_000L;         // 5 分钟前：未过期
        PairEntity expired1 = entity("EXPIRED1", 11L, null, PairEntity.STATUS_PENDING, expiredTs);
        PairEntity expired2 = entity("EXPIRED2", null, null, PairEntity.STATUS_PENDING, expiredTs);
        PairEntity fresh1 = entity("FRESH1", 22L, null, PairEntity.STATUS_PENDING, freshTs);
        PairEntity done1 = entity("DONE1", 33L, 44L, PairEntity.STATUS_COMPLETE, expiredTs);
        when(repo.findAll()).thenReturn(List.of(expired1, expired2, fresh1, done1));
        // removePairRow 内部先 findByPairCode 再 delete
        when(repo.findByPairCode("EXPIRED1")).thenReturn(expired1);
        when(repo.findByPairCode("EXPIRED2")).thenReturn(expired2);

        PairService service = new PairService(repo);
        service.loadFromDb();

        // 过期 PENDING：两行均被删除、未注册
        verify(repo, times(2)).delete(any(PairEntity.class));
        assertNull(service.getPairOfUser(11L));
        // 未过期 PENDING 与 COMPLETE：保留
        assertNotNull(service.getPairOfUser(22L));
        assertNotNull(service.getPairOfUser(33L));
        assertNotNull(service.getPairOfUser(44L));
    }

    @Test
    void loadFromDb_noExpired_keepsAll() {
        PairRepo repo = mock(PairRepo.class);
        long now = System.currentTimeMillis();
        when(repo.findAll()).thenReturn(List.of(
                entity("FRESH1", 22L, null, PairEntity.STATUS_PENDING, now - 5 * 60_000L),
                entity("DONE1", 33L, 44L, PairEntity.STATUS_COMPLETE, now - 60 * 60_000L)
        ));

        PairService service = new PairService(repo);
        service.loadFromDb();

        verify(repo, never()).delete(any(PairEntity.class));
        assertNotNull(service.getPairOfUser(22L));
        assertNotNull(service.getPairOfUser(33L));
        assertNotNull(service.getPairOfUser(44L));
    }
}
