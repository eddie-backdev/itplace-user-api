package com.itplace.userapi.ai.rag.index;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class BenefitIndexer implements ApplicationRunner {

    private final BenefitRagSyncService benefitRagSyncService;

    @Value("${app.ai.benefits.seed.enabled:true}")
    private boolean seedEnabled;

    @Override
    public void run(ApplicationArguments args) {
        if (!seedEnabled) {
            log.info("혜택 ES 초기 동기화가 비활성화되어 있습니다.");
            return;
        }

        Thread indexThread = new Thread(this::syncBenefitsSafely, "benefit-rag-initial-sync");
        indexThread.setDaemon(true);
        indexThread.start();
    }

    private void syncBenefitsSafely() {
        try {
            BenefitRagSyncService.SyncResult result = benefitRagSyncService.syncAll();
            log.info("혜택 RAG 초기 동기화 완료: {}", result);
        } catch (Exception e) {
            log.warn("혜택 RAG 초기 동기화 실패 (ES/OpenAI 설정이 비활성 상태일 수 있음): {}", e.getMessage());
        }
    }
}
