package com.itplace.userapi.benefit.service;

import com.itplace.userapi.benefit.dto.request.BenefitSnapshotImportRequest;
import com.itplace.userapi.benefit.dto.response.BenefitSnapshotImportResponse;

public interface BenefitImportService {
    BenefitSnapshotImportResponse importSnapshot(BenefitSnapshotImportRequest request, String apiKey);
}
