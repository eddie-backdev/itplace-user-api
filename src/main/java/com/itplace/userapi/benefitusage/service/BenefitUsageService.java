package com.itplace.userapi.benefitusage.service;

import com.itplace.userapi.benefitusage.dto.request.CreateBenefitUsageRecordRequest;
import com.itplace.userapi.benefitusage.dto.request.UpdateBenefitUsageRequest;
import com.itplace.userapi.benefitusage.dto.response.BenefitUsageMonthResponse;
import com.itplace.userapi.benefitusage.dto.response.BenefitUsageRecordResponse;

public interface BenefitUsageService {
    BenefitUsageMonthResponse getMonth(Long userId, String month);

    BenefitUsageRecordResponse create(Long userId, CreateBenefitUsageRecordRequest request);

    BenefitUsageRecordResponse update(Long userId, Long recordId, UpdateBenefitUsageRequest request);

    void delete(Long userId, Long recordId);
}
