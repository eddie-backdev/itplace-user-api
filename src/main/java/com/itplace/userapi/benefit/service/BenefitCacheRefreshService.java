package com.itplace.userapi.benefit.service;

import com.itplace.userapi.map.service.PartnerBenefitCacheService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 공유 PostgreSQL의 변경 대기열을 처리한다. Redis 실패 시 DB 작업을 남겨 재시도한다. */
@Slf4j
@Service
@RequiredArgsConstructor
public class BenefitCacheRefreshService {
    private static final int BATCH_SIZE = 100;
    private final JdbcTemplate jdbcTemplate;
    private final PlatformTransactionManager transactionManager;
    private final PartnerBenefitCacheService cacheService;

    public int refreshPending() {
        int refreshed = 0;
        // 하나의 내부 요청이 무제한 실행되지 않게 제한한다. 나머지는 다음 실행에서 처리한다.
        for (int i = 0; i < 10; i++) {
            int count = refreshBatch();
            refreshed += count;
            if (count < BATCH_SIZE) break;
        }
        return refreshed;
    }

    private int refreshBatch() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setTimeout(15);
        return transaction.execute(status -> {
            List<Long> ids = jdbcTemplate.queryForList("""
                    SELECT partner_id FROM benefit_cache_refresh
                    ORDER BY changed_at, partner_id LIMIT 100 FOR UPDATE SKIP LOCKED
                    """, Long.class);
            if (ids.isEmpty()) return 0;
            cacheService.refreshBenefits(ids);
            jdbcTemplate.batchUpdate("DELETE FROM benefit_cache_refresh WHERE partner_id = ?",
                    ids, BATCH_SIZE, (statement, id) -> statement.setLong(1, id));
            return ids.size();
        });
    }

    public void refreshAfterCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { retryPending(); }
            });
        } else {
            retryPending();
        }
    }

    @Scheduled(fixedDelayString = "${app.benefit-cache.retry-delay-ms:5000}",
            initialDelayString = "${app.benefit-cache.retry-delay-ms:5000}")
    public void retryPending() {
        try {
            refreshPending();
        } catch (RuntimeException failure) {
            log.warn("혜택 캐시 갱신 실패: 변경 대기열을 보존하고 재시도합니다.", failure);
        }
    }
}
