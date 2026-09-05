package com.itplace.userapi.ai.rag.index;

import com.itplace.userapi.benefit.entity.Benefit;
import com.itplace.userapi.benefit.repository.BenefitRepository;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class BenefitRagSourceQueryService {

    private final BenefitRepository benefitRepository;
    private final BenefitRagDocumentBuilder documentBuilder;

    /**
     * RAG 원천 엔티티를 읽기 트랜잭션 안에서 완전한 문서 snapshot으로 변환한다.
     * embedding과 Elasticsearch I/O는 이 메서드가 반환된 뒤 실행되어 DB 커넥션을 점유하지 않는다.
     */
    @Transactional(readOnly = true)
    public SourceSnapshot loadSourceSnapshot() {
        List<BenefitRagDocumentBuilder.PendingBenefitDocument> pendingDocuments = new ArrayList<>();
        int scannedBenefits = 0;
        for (Benefit benefit : benefitRepository.findAllWithPartnerAndTierBenefits()) {
            scannedBenefits++;
            pendingDocuments.addAll(documentBuilder.buildPendingDocuments(benefit));
        }
        return new SourceSnapshot(scannedBenefits, List.copyOf(pendingDocuments));
    }

    public record SourceSnapshot(
            int scannedBenefits,
            List<BenefitRagDocumentBuilder.PendingBenefitDocument> pendingDocuments
    ) {
    }
}
