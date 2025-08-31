//package com.itplace.userapi.ai.rag.index;
//
//import co.elastic.clients.elasticsearch.ElasticsearchClient;
//import com.itplace.userapi.ai.rag.document.BenefitDocument;
//import com.itplace.userapi.ai.rag.service.ElasticService;
//import com.itplace.userapi.ai.rag.service.EmbeddingService;
//import com.itplace.userapi.benefit.entity.Benefit;
//import com.itplace.userapi.benefit.entity.TierBenefit;
//import com.itplace.userapi.benefit.repository.BenefitRepository;
//import com.itplace.userapi.partner.entity.Partner;
//import java.util.List;
//import lombok.RequiredArgsConstructor;
//import org.springframework.boot.ApplicationArguments;
//import org.springframework.boot.ApplicationRunner;
//import org.springframework.context.annotation.Configuration;
//
//@Configuration
//@RequiredArgsConstructor
//public class ElasticsearchIndexer implements ApplicationRunner {
//    private final ElasticsearchClient esClient;
//    private final BenefitRepository benefitRepo;
//    private final EmbeddingService embeddingService;
//    private final ElasticService elasticService;
//
//    @Override
//    public void run(ApplicationArguments args) throws Exception {
//        final String indexName = "benefit";
//        // 인덱스가 없으면 생성
//        elasticService.createIndexIfNotExists(indexName);
//
//        benefitRepo.findAllWithPartnerAndTierBenefits().forEach(b -> {
//            try {
//                String benefitId = b.getBenefitId().toString();
//                if (elasticService.existsById(indexName, benefitId)) {
//                    System.out.println("Benefit already exists: " + benefitId);
//                    return;
//                }
//
//                Partner p = b.getPartner();
//
//                String embeddingText = String.format(
//                        """
//                                이 혜택은 '%s' 카테고리에 해당하며, '%s' 제휴사의 '%s' 혜택입니다.
//                                """,
//                        p.getCategory(),
//                        p.getPartnerName(),
//                        b.getBenefitName()
//                );
//
//                List<Float> embedding = embeddingService.embed(embeddingText);
//
//                BenefitDocument doc = BenefitDocument.builder()
//                        .benefitId(benefitId)
//                        .benefitName(b.getBenefitName())
//                        .partnerId(p.getPartnerId().toString())
//                        .partnerName(p.getPartnerName())
//                        .category(p.getCategory())
//                        .description(b.getDescription())
//                        .embedding(embedding)
//                        .build();
//
//                esClient.index(i -> i
//                        .index(indexName)
//                        .id(benefitId)
//                        .document(doc)
//                );
//                Thread.sleep(500);
//                System.out.println("Indexed " + benefitId);
//
//            } catch (Exception e) {
//                System.err.println("error: " + b.getBenefitId());
//                e.printStackTrace();
//            }
//        });
//    }
//
//    private String getBenefitTextForGrade(Benefit b, String grade) {
//        return b.getTierBenefits().stream()
//                .filter(tb -> tb.getGrade().name().equalsIgnoreCase(grade))
//                .findFirst()
//                .map(TierBenefit::getContext)
//                .orElse("해당 등급 혜택 없음");
//    }
//
//}
