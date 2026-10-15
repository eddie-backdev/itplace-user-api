package com.itplace.userapi.benefitusage.repository;

import com.itplace.userapi.benefitusage.entity.BenefitUsageRecord;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BenefitUsageRecordRepository extends JpaRepository<BenefitUsageRecord, Long> {
    List<BenefitUsageRecord> findByUser_IdAndUsedAtGreaterThanEqualAndUsedAtLessThanOrderByUsedAtDescRecordIdDesc(
            Long userId, LocalDate startInclusive, LocalDate endExclusive);

    Optional<BenefitUsageRecord> findByRecordIdAndUser_Id(Long recordId, Long userId);

    long deleteByRecordIdAndUser_Id(Long recordId, Long userId);
}
