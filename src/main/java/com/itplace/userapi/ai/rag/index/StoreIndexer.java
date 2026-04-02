package com.itplace.userapi.ai.rag.index;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.itplace.userapi.ai.rag.document.StoreDocument;
import com.itplace.userapi.ai.rag.service.ElasticService;
import com.itplace.userapi.map.repository.StoreRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
@Slf4j
public class StoreIndexer implements ApplicationRunner {

    private final ElasticsearchClient esClient;
    private final StoreRepository storeRepository;
    private final ElasticService elasticService;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        try {
            elasticService.createStoreIndexIfNotExists();

            storeRepository.findAllWithPartner().forEach(store -> {
                try {
                    String storeId = String.valueOf(store.getStoreId());
                    if (elasticService.existsById("store", storeId)) {
                        return;
                    }

                    StoreDocument doc = StoreDocument.builder()
                            .storeId(store.getStoreId())
                            .storeName(store.getStoreName())
                            .business(store.getBusiness())
                            .partnerName(store.getPartner().getPartnerName())
                            .category(store.getPartner().getCategory())
                            .city(store.getCity())
                            .town(store.getTown())
                            .build();

                    esClient.index(i -> i
                            .index("store")
                            .id(storeId)
                            .document(doc)
                    );
                } catch (Exception e) {
                    log.error("매장 인덱싱 실패: storeId={}", store.getStoreId(), e);
                }
            });
            log.info("매장 ES 인덱싱 완료");
        } catch (Exception e) {
            log.warn("매장 ES 인덱싱 실패 (ES가 비활성 상태일 수 있음): {}", e.getMessage());
        }
    }
}
