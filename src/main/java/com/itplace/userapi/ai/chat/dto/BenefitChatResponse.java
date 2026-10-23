package com.itplace.userapi.ai.chat.dto;

import com.itplace.userapi.benefit.dto.BenefitOffer;
import com.itplace.userapi.benefit.dto.BenefitSourceBlock;
import com.itplace.userapi.benefit.dto.NormalizationIssue;
import com.itplace.userapi.benefit.dto.ChannelDecision;
import com.itplace.userapi.benefit.dto.OfferResolution;
import java.util.List;

public record BenefitChatResponse(String reason, List<BenefitChatPartner> partners, String status,
                                  List<String> suggestedQuestions, String traceId) {
    public BenefitChatResponse {
        partners = partners == null ? List.of() : List.copyOf(partners);
        suggestedQuestions = suggestedQuestions == null ? List.of() : List.copyOf(suggestedQuestions);
    }

    public record BenefitChatPartner(Long partnerId, Long benefitId, String partnerName, String benefitName,
                                     String category, Long representativeStoreId, String representativeStoreName,
                                     Integer storeCount, String benefitText, String imgUrl, String candidateKey,
                                     Long policyId, Long tierBenefitId, String carrier, String grade,
                                     String usageType, String sourceUrl, String sourceCapturedAt,
                                     List<BenefitOffer> offers, OfferResolution offerResolution,
                                     ChannelDecision channelDecision, List<BenefitSourceBlock> sourceBlocks,
                                     List<NormalizationIssue> normalizationIssues) {
        public BenefitChatPartner(Long partnerId, Long benefitId, String partnerName, String benefitName,
                                  String category, Long representativeStoreId, String representativeStoreName,
                                  Integer storeCount, String benefitText, String imgUrl, String candidateKey,
                                  Long policyId, Long tierBenefitId, String carrier, String grade, String usageType,
                                  String sourceUrl, String sourceCapturedAt, List<BenefitOffer> offers) {
            this(partnerId, benefitId, partnerName, benefitName, category, representativeStoreId,
                    representativeStoreName, storeCount, benefitText, imgUrl, candidateKey, policyId,
                    tierBenefitId, carrier, grade, usageType, sourceUrl, sourceCapturedAt, offers,
                    null, null, List.of(), List.of());
        }

        public BenefitChatPartner {
            offers = offers == null ? List.of() : List.copyOf(offers);
            sourceBlocks = sourceBlocks == null ? List.of() : List.copyOf(sourceBlocks);
            normalizationIssues = normalizationIssues == null ? List.of() : List.copyOf(normalizationIssues);
        }
    }
}
