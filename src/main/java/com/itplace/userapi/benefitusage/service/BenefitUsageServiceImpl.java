package com.itplace.userapi.benefitusage.service;

import com.itplace.userapi.benefit.BenefitCode;
import com.itplace.userapi.benefit.entity.Benefit;
import com.itplace.userapi.benefit.exception.BenefitNotFoundException;
import com.itplace.userapi.benefit.repository.BenefitRepository;
import com.itplace.userapi.benefit.repository.BenefitCarrierPolicyRepository;
import com.itplace.userapi.benefitusage.BenefitUsageCode;
import com.itplace.userapi.benefitusage.dto.request.CreateBenefitUsageRecordRequest;
import com.itplace.userapi.benefitusage.dto.request.UpdateBenefitUsageRequest;
import com.itplace.userapi.benefitusage.dto.response.BenefitUsageMonthResponse;
import com.itplace.userapi.benefitusage.dto.response.BenefitUsageRecordResponse;
import com.itplace.userapi.benefitusage.entity.BenefitUsageRecord;
import com.itplace.userapi.benefitusage.exception.BenefitUsageException;
import com.itplace.userapi.benefitusage.repository.BenefitUsageRecordRepository;
import com.itplace.userapi.security.SecurityCode;
import com.itplace.userapi.user.entity.User;
import com.itplace.userapi.user.exception.UserNotFoundException;
import com.itplace.userapi.user.repository.UserRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class BenefitUsageServiceImpl implements BenefitUsageService {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Pattern MONTH_FORMAT = Pattern.compile("[1-9]\\d{3}-(0[1-9]|1[0-2])");

    private final BenefitUsageRecordRepository recordRepository;
    private final UserRepository userRepository;
    private final BenefitRepository benefitRepository;
    private final BenefitCarrierPolicyRepository benefitCarrierPolicyRepository;

    @Override
    @Transactional(readOnly = true)
    public BenefitUsageMonthResponse getMonth(Long userId, String month) {
        if (month == null || !MONTH_FORMAT.matcher(month).matches()) {
            throw new BenefitUsageException(BenefitUsageCode.INVALID_MONTH);
        }
        YearMonth yearMonth = YearMonth.parse(month);
        List<BenefitUsageRecord> records = recordRepository
                .findByUser_IdAndUsedAtGreaterThanEqualAndUsedAtLessThanOrderByUsedAtDescRecordIdDesc(
                        userId, yearMonth.atDay(1), yearMonth.plusMonths(1).atDay(1));
        long totalSavedAmount = records.stream()
                .map(BenefitUsageRecord::getSavedAmountKrw)
                .filter(amount -> amount != null)
                .mapToLong(Long::longValue)
                .sum();
        int unknownAmountCount = (int) records.stream()
                .filter(record -> record.getSavedAmountKrw() == null)
                .count();
        return new BenefitUsageMonthResponse(
                yearMonth.toString(), records.size(), totalSavedAmount, unknownAmountCount,
                records.stream().map(BenefitUsageRecordResponse::from).toList());
    }

    @Override
    public BenefitUsageRecordResponse create(Long userId, CreateBenefitUsageRecordRequest request) {
        validateUsedAt(request.usedAt());
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(SecurityCode.USER_NOT_FOUND));
        Benefit benefit = benefitRepository.findAllByIdWithPartner(List.of(request.benefitId())).stream()
                .findFirst()
                .orElseThrow(() -> new BenefitNotFoundException(BenefitCode.BENEFIT_NOT_FOUND));
        var partner = benefit.getPartner();
        String benefitName = benefitCarrierPolicyRepository.findAllByBenefitIn(List.of(benefit)).stream()
                .filter(policy -> !Boolean.FALSE.equals(policy.getActive()))
                .findFirst().map(policy -> policy.getDisplayBenefitName()).orElse(benefit.getBenefitName());
        BenefitUsageRecord record = new BenefitUsageRecord(
                user, benefit.getBenefitId(), partner.getPartnerId(), benefitName,
                partner.getPartnerName(), partner.getImage(), request.usedAt(),
                amount(request.savedAmountKrw()), request.memo());
        return BenefitUsageRecordResponse.from(recordRepository.save(record));
    }

    @Override
    public BenefitUsageRecordResponse update(Long userId, Long recordId, UpdateBenefitUsageRequest request) {
        validateUsedAt(request.usedAt());
        BenefitUsageRecord record = recordRepository.findByRecordIdAndUser_Id(recordId, userId)
                .orElseThrow(() -> new BenefitUsageException(BenefitUsageCode.RECORD_NOT_FOUND));
        record.updateUsageDetails(request.usedAt(), amount(request.savedAmountKrw()), request.memo());
        return BenefitUsageRecordResponse.from(record);
    }

    @Override
    public void delete(Long userId, Long recordId) {
        if (recordRepository.deleteByRecordIdAndUser_Id(recordId, userId) == 0) {
            throw new BenefitUsageException(BenefitUsageCode.RECORD_NOT_FOUND);
        }
    }

    private void validateUsedAt(LocalDate usedAt) {
        if (usedAt.getYear() < 1000) {
            throw new BenefitUsageException(BenefitUsageCode.INVALID_DATE);
        }
        if (usedAt.isAfter(LocalDate.now(SEOUL))) {
            throw new BenefitUsageException(BenefitUsageCode.DATE_IN_FUTURE);
        }
    }

    private Long amount(BigDecimal savedAmountKrw) {
        return savedAmountKrw == null ? null : savedAmountKrw.longValueExact();
    }
}
