package com.itplace.userapi.ai.rag.postgres;

import com.fasterxml.jackson.databind.JsonNode;

public record BenefitSearchHit(long benefitId, long policyId, Long tierBenefitId, double score, JsonNode metadata) {}
