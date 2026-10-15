package com.itplace.userapi.benefitusage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.itplace.userapi.benefit.entity.Benefit;
import com.itplace.userapi.benefit.repository.BenefitRepository;
import com.itplace.userapi.benefit.repository.BenefitCarrierPolicyRepository;
import com.itplace.userapi.benefitusage.BenefitUsageCode;
import com.itplace.userapi.benefitusage.dto.request.CreateBenefitUsageRecordRequest;
import com.itplace.userapi.benefitusage.dto.request.UpdateBenefitUsageRequest;
import com.itplace.userapi.benefitusage.entity.BenefitUsageRecord;
import com.itplace.userapi.benefitusage.exception.BenefitUsageException;
import com.itplace.userapi.benefitusage.repository.BenefitUsageRecordRepository;
import com.itplace.userapi.partner.entity.Partner;
import com.itplace.userapi.user.entity.User;
import com.itplace.userapi.user.repository.UserRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class BenefitUsageServiceImplTest {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Mock
    private BenefitUsageRecordRepository recordRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private BenefitRepository benefitRepository;
    @Mock
    private BenefitCarrierPolicyRepository benefitCarrierPolicyRepository;
    @InjectMocks
    private BenefitUsageServiceImpl service;

    private User user;
    private Benefit benefit;

    @BeforeEach
    void setUp() {
        user = User.builder().id(7L).build();
        benefit = Benefit.builder()
                .benefitId(20L)
                .benefitName("화요일 프레시푸드 할인")
                .partner(Partner.builder().partnerId(30L).partnerName("GS25").image("/gs25.png").build())
                .build();
    }

    @Test
    void getMonthUsesPrincipalOwnerAndSeparatesKnownAndUnknownSavings() {
        YearMonth month = YearMonth.of(2026, 9);
        List<BenefitUsageRecord> records = List.of(
                record(LocalDate.of(2026, 9, 20), 0L),
                record(LocalDate.of(2026, 9, 15), null),
                record(LocalDate.of(2026, 9, 10), 1_250L));
        when(recordRepository
                .findByUser_IdAndUsedAtGreaterThanEqualAndUsedAtLessThanOrderByUsedAtDescRecordIdDesc(
                        7L, month.atDay(1), month.plusMonths(1).atDay(1)))
                .thenReturn(records);

        var result = service.getMonth(7L, "2026-09");

        assertThat(result.month()).isEqualTo("2026-09");
        assertThat(result.recordCount()).isEqualTo(3);
        assertThat(result.totalSavedAmountKrw()).isEqualTo(1_250L);
        assertThat(result.unknownAmountCount()).isEqualTo(1);
        assertThat(result.records()).extracting("savedAmountKrw").containsExactly(0L, null, 1_250L);
        verify(recordRepository)
                .findByUser_IdAndUsedAtGreaterThanEqualAndUsedAtLessThanOrderByUsedAtDescRecordIdDesc(
                        7L, month.atDay(1), month.plusMonths(1).atDay(1));
    }

    @Test
    void createPersistsSourceSnapshotsAndAcceptsZeroSavingsForPastUse() {
        LocalDate usedAt = LocalDate.now(SEOUL).minusDays(1);
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(benefitRepository.findAllByIdWithPartner(List.of(20L))).thenReturn(List.of(benefit));
        when(recordRepository.save(any())).thenAnswer(invocation -> {
            BenefitUsageRecord record = invocation.getArgument(0);
            ReflectionTestUtils.setField(record, "recordId", 99L);
            return record;
        });

        var result = service.create(7L,
                new CreateBenefitUsageRecordRequest(20L, usedAt, BigDecimal.ZERO, "  어제 사용  "));

        ArgumentCaptor<BenefitUsageRecord> captor = ArgumentCaptor.forClass(BenefitUsageRecord.class);
        verify(recordRepository).save(captor.capture());
        BenefitUsageRecord saved = captor.getValue();
        assertThat(result.recordId()).isEqualTo(99L);
        assertThat(saved.getUser()).isSameAs(user);
        assertThat(saved.getBenefitId()).isEqualTo(20L);
        assertThat(saved.getPartnerId()).isEqualTo(30L);
        assertThat(saved.getBenefitName()).isEqualTo("화요일 프레시푸드 할인");
        assertThat(saved.getPartnerName()).isEqualTo("GS25");
        assertThat(saved.getPartnerImage()).isEqualTo("/gs25.png");
        assertThat(saved.getUsedAt()).isEqualTo(usedAt);
        assertThat(saved.getSavedAmountKrw()).isZero();
        assertThat(saved.getMemo()).isEqualTo("어제 사용");
    }

    @Test
    void createAcceptsKoreanToday() {
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(benefitRepository.findAllByIdWithPartner(List.of(20L))).thenReturn(List.of(benefit));
        when(recordRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.create(7L, new CreateBenefitUsageRecordRequest(20L, LocalDate.now(SEOUL), null, null));

        verify(recordRepository).save(any());
    }

    @Test
    void createAcceptsFirstSupportedYear() {
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(benefitRepository.findAllByIdWithPartner(List.of(20L))).thenReturn(List.of(benefit));
        when(recordRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.create(7L,
                new CreateBenefitUsageRecordRequest(20L, LocalDate.of(1000, 1, 1), null, null));

        verify(recordRepository).save(any());
    }

    @Test
    void createRejectsDateAfterKoreanToday() {
        LocalDate tomorrowInSeoul = LocalDate.now(SEOUL).plusDays(1);

        assertThatThrownBy(() -> service.create(7L,
                new CreateBenefitUsageRecordRequest(20L, tomorrowInSeoul, null, null)))
                .isInstanceOf(BenefitUsageException.class)
                .extracting("code")
                .isEqualTo(BenefitUsageCode.DATE_IN_FUTURE);
    }

    @Test
    void createRejectsDateBelowFirstSupportedYear() {
        assertThatThrownBy(() -> service.create(7L,
                new CreateBenefitUsageRecordRequest(20L, LocalDate.of(999, 12, 31), null, null)))
                .isInstanceOf(BenefitUsageException.class)
                .extracting("code")
                .isEqualTo(BenefitUsageCode.INVALID_DATE);
    }

    @Test
    void getMonthRejectsYearZero() {
        assertThatThrownBy(() -> service.getMonth(7L, "0000-01"))
                .isInstanceOf(BenefitUsageException.class)
                .extracting("code")
                .isEqualTo(BenefitUsageCode.INVALID_MONTH);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-13", "2026-00", "26-09", "2026/09", "2026-9"})
    void getMonthRejectsMalformedMonthBeforeRepositoryRead(String month) {
        assertThatThrownBy(() -> service.getMonth(7L, month))
                .isInstanceOf(BenefitUsageException.class)
                .extracting("code")
                .isEqualTo(BenefitUsageCode.INVALID_MONTH);
        org.mockito.Mockito.verifyNoInteractions(recordRepository);
    }

    @Test
    void deleteOnlyDeletesForPrincipalOwnerAndHidesOtherOwnersRecord() {
        when(recordRepository.deleteByRecordIdAndUser_Id(99L, 7L)).thenReturn(0L);

        assertThatThrownBy(() -> service.delete(7L, 99L))
                .isInstanceOf(BenefitUsageException.class)
                .extracting("code")
                .isEqualTo(BenefitUsageCode.RECORD_NOT_FOUND);
        verify(recordRepository).deleteByRecordIdAndUser_Id(99L, 7L);
    }

    @Test
    void updateKeepsSnapshotsAndMovesRecordIntoNewMonthSummary() {
        YearMonth month = YearMonth.now(SEOUL);
        BenefitUsageRecord record = record(month.minusMonths(1).atEndOfMonth(), null);
        ReflectionTestUtils.setField(record, "recordId", 99L);
        when(recordRepository.findByRecordIdAndUser_Id(99L, 7L)).thenReturn(Optional.of(record));
        when(recordRepository
                .findByUser_IdAndUsedAtGreaterThanEqualAndUsedAtLessThanOrderByUsedAtDescRecordIdDesc(
                        7L, month.atDay(1), month.plusMonths(1).atDay(1)))
                .thenReturn(List.of(record));

        var updated = service.update(7L, 99L,
                new UpdateBenefitUsageRequest(LocalDate.now(SEOUL), BigDecimal.valueOf(500L), "  수정한 기록  "));
        var summary = service.getMonth(7L, month.toString());

        assertThat(updated.recordId()).isEqualTo(99L);
        assertThat(updated.benefitName()).isEqualTo("혜택");
        assertThat(updated.partnerId()).isEqualTo(30L);
        assertThat(updated.savedAmountKrw()).isEqualTo(500L);
        assertThat(updated.memo()).isEqualTo("수정한 기록");
        assertThat(summary.month()).isEqualTo(month.toString());
        assertThat(summary.totalSavedAmountKrw()).isEqualTo(500L);
        assertThat(summary.unknownAmountCount()).isZero();
        verify(recordRepository).findByRecordIdAndUser_Id(99L, 7L);
    }

    @Test
    void updateReturnsSameNotFoundForRecordOwnedBySomeoneElse() {
        when(recordRepository.findByRecordIdAndUser_Id(99L, 7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(7L, 99L,
                new UpdateBenefitUsageRequest(LocalDate.now(SEOUL), null, null)))
                .isInstanceOf(BenefitUsageException.class)
                .extracting("code")
                .isEqualTo(BenefitUsageCode.RECORD_NOT_FOUND);
        verify(recordRepository).findByRecordIdAndUser_Id(99L, 7L);
    }

    @Test
    void createReturnsNotFoundForMissingBenefit() {
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(benefitRepository.findAllByIdWithPartner(List.of(20L))).thenReturn(List.of());

        assertThatThrownBy(() -> service.create(7L,
                new CreateBenefitUsageRecordRequest(20L, LocalDate.now(SEOUL), null, null)))
                .isInstanceOf(com.itplace.userapi.benefit.exception.BenefitNotFoundException.class);
    }

    private BenefitUsageRecord record(LocalDate usedAt, Long savedAmountKrw) {
        return new BenefitUsageRecord(user, 20L, 30L, "혜택", "파트너", null, usedAt, savedAmountKrw, null);
    }
}
