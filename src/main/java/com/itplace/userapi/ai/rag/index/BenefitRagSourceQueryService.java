package com.itplace.userapi.ai.rag.index;

import com.itplace.userapi.benefit.entity.Benefit;
import com.itplace.userapi.benefit.entity.BenefitCarrierPolicy;
import com.itplace.userapi.benefit.entity.CarrierTierBenefit;
import com.itplace.userapi.benefit.repository.BenefitCarrierPolicyRepository;
import com.itplace.userapi.benefit.repository.BenefitRepository;
import com.itplace.userapi.benefit.repository.CarrierTierBenefitRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class BenefitRagSourceQueryService {

    private final BenefitRepository benefitRepository;
    private final BenefitRagDocumentBuilder documentBuilder;
    private final BenefitCarrierPolicyRepository policyRepository;
    private final CarrierTierBenefitRepository tierRepository;
    private static final int SOURCE_BATCH_SIZE = 200;

    /**
     * RAG 원천 엔티티를 읽기 트랜잭션 안에서 완전한 문서 snapshot으로 변환한다.
     * embedding과 Elasticsearch I/O는 이 메서드가 반환된 뒤 실행되어 DB 커넥션을 점유하지 않는다.
     */
    @Transactional(readOnly = true)
    public SourceSnapshot loadSourceSnapshot() {
        List<BenefitRagDocumentBuilder.PendingBenefitDocument> pendingDocuments = new ArrayList<>();
        List<Benefit> benefits = benefitRepository.findAllWithPartnerAndTierBenefits();
        for (int offset = 0; offset < benefits.size(); offset += SOURCE_BATCH_SIZE) {
            List<Benefit> batch = benefits.subList(offset, Math.min(offset + SOURCE_BATCH_SIZE, benefits.size()));
            List<BenefitCarrierPolicy> policies = policyRepository.findAllByBenefitIn(batch);
            Map<Long, List<BenefitCarrierPolicy>> policiesByBenefit = policies.stream()
                    .collect(Collectors.groupingBy(policy -> policy.getBenefit().getBenefitId()));
            Map<Long, List<CarrierTierBenefit>> tiersByPolicy = policies.isEmpty() ? Map.of()
                    : tierRepository.findAllByBenefitCarrierPolicyIn(policies).stream()
                    .collect(Collectors.groupingBy(tier -> tier.getBenefitCarrierPolicy().getBenefitCarrierPolicyId()));
            for (Benefit benefit : batch) {
                List<BenefitCarrierPolicy> benefitPolicies = policiesByBenefit.getOrDefault(benefit.getBenefitId(), List.of());
                List<CarrierTierBenefit> tiers = benefitPolicies.stream()
                        .flatMap(policy -> tiersByPolicy.getOrDefault(policy.getBenefitCarrierPolicyId(), List.of()).stream()).toList();
                pendingDocuments.addAll(documentBuilder.buildPendingDocuments(benefit, benefitPolicies, tiers));
            }
        }
        return new SourceSnapshot(benefits.size(), List.copyOf(pendingDocuments));
    }

    public record SourceSnapshot(
            int scannedBenefits,
            List<BenefitRagDocumentBuilder.PendingBenefitDocument> pendingDocuments
    ) {
    }
}
