package com.itplace.userapi.ai.rag.index;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.itplace.userapi.ai.rag.document.BenefitDocument;
import com.itplace.userapi.ai.rag.service.ElasticService;
import com.itplace.userapi.ai.rag.service.EmbeddingService;
import com.itplace.userapi.benefit.entity.Benefit;
import com.itplace.userapi.benefit.entity.BenefitCarrierPolicy;
import com.itplace.userapi.benefit.entity.CarrierTierBenefit;
import com.itplace.userapi.benefit.repository.BenefitCarrierPolicyRepository;
import com.itplace.userapi.benefit.repository.BenefitRepository;
import com.itplace.userapi.benefit.repository.CarrierTierBenefitRepository;
import java.util.List;
import java.util.Objects;
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
    private static final String INDEX_NAME = "benefit";

    private final ElasticsearchClient esClient;
    private final BenefitRepository benefitRepository;
    private final BenefitCarrierPolicyRepository benefitCarrierPolicyRepository;
    private final CarrierTierBenefitRepository carrierTierBenefitRepository;
    private final ElasticService elasticService;
    private final EmbeddingService embeddingService;

    @Value("${app.ai.benefits.seed.enabled:true}")
    private boolean seedEnabled;

    @Value("${app.ai.benefits.seed.delay-ms:100}")
    private long delayMs;

    @Override
    public void run(ApplicationArguments args) {
        if (!seedEnabled) {
            log.info("혜택 ES 초기 색인이 비활성화되어 있습니다.");
            return;
        }

        try {
            elasticService.createIndexIfNotExists(INDEX_NAME);
        } catch (Exception e) {
            log.warn("혜택 ES 인덱스 생성 실패 (ES가 비활성 상태일 수 있음): {}", e.getMessage());
            return;
        }

        Thread indexThread = new Thread(this::indexBenefitsSafely, "benefit-es-indexer");
        indexThread.setDaemon(true);
        indexThread.start();
    }

    private void indexBenefitsSafely() {
        try {
            int indexed = indexBenefits();
            log.info("혜택 ES 초기 색인 완료: indexed={}", indexed);
        } catch (Exception e) {
            log.warn("혜택 ES 초기 색인 실패 (ES/OpenAI 설정이 비활성 상태일 수 있음): {}", e.getMessage());
        }
    }

    private int indexBenefits() throws Exception {
        int indexed = 0;
        for (Benefit benefit : benefitRepository.findAllWithPartnerAndTierBenefits()) {
            if (Boolean.FALSE.equals(benefit.getActive())) {
                continue;
            }

            String benefitId = String.valueOf(benefit.getBenefitId());
            if (elasticService.existsById(INDEX_NAME, benefitId)) {
                continue;
            }

            BenefitDocument document = toDocument(benefit);
            if (document == null) {
                continue;
            }

            esClient.index(i -> i
                    .index(INDEX_NAME)
                    .id(benefitId)
                    .document(document)
            );
            indexed++;

            if (delayMs > 0) {
                Thread.sleep(delayMs);
            }
        }
        return indexed;
    }

    private BenefitDocument toDocument(Benefit benefit) {
        if (benefit.getPartner() == null) {
            return null;
        }

        List<BenefitCarrierPolicy> policies = benefitCarrierPolicyRepository.findAllByBenefitIn(List.of(benefit)).stream()
                .filter(policy -> !Boolean.FALSE.equals(policy.getActive()))
                .toList();
        String description = summarizeDescriptions(policies);
        String context = summarizeTierContexts(policies);
        String searchableText = String.join("\n",
                nullToBlank(benefit.getPartner().getPartnerName()),
                nullToBlank(benefit.getPartner().getCategory()),
                nullToBlank(benefit.getBenefitName()),
                description,
                context
        ).trim();

        if (searchableText.isBlank()) {
            return null;
        }

        return BenefitDocument.builder()
                .id(String.valueOf(benefit.getBenefitId()))
                .embedding(embeddingService.embed(searchableText))
                .partnerId(String.valueOf(benefit.getPartner().getPartnerId()))
                .partnerName(benefit.getPartner().getPartnerName())
                .benefitId(String.valueOf(benefit.getBenefitId()))
                .benefitName(benefit.getBenefitName())
                .category(benefit.getPartner().getCategory())
                .description(description)
                .build();
    }

    private String summarizeDescriptions(List<BenefitCarrierPolicy> policies) {
        return policies.stream()
                .map(BenefitCarrierPolicy::getDescription)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(description -> !description.isBlank())
                .distinct()
                .limit(3)
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }

    private String summarizeTierContexts(List<BenefitCarrierPolicy> policies) {
        if (policies.isEmpty()) {
            return "";
        }

        List<CarrierTierBenefit> tierBenefits = carrierTierBenefitRepository.findAllByBenefitCarrierPolicyIn(policies);
        return tierBenefits.stream()
                .map(CarrierTierBenefit::getContext)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(context -> !context.isBlank())
                .distinct()
                .limit(5)
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }

    private String nullToBlank(String value) {
        return value == null ? "" : value;
    }
}
