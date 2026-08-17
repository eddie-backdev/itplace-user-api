package com.itplace.userapi.log.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.itplace.userapi.benefit.entity.Benefit;
import com.itplace.userapi.benefit.repository.BenefitRepository;
import com.itplace.userapi.log.dto.ResponseLogCommand;
import com.itplace.userapi.log.entity.LogDocument;
import com.itplace.userapi.log.repository.LogRepository;
import com.itplace.userapi.partner.entity.Partner;
import com.itplace.userapi.partner.repository.PartnerRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class LogServiceImplTest {

    @Mock
    private LogRepository logRepository;

    @Mock
    private BenefitRepository benefitRepository;

    @Mock
    private PartnerRepository partnerRepository;

    private LogServiceImpl logService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        logService = new LogServiceImpl(logRepository, benefitRepository, partnerRepository);
    }

    @Test
    void searchRank_returnsEmptyListWhenMongoAggregationFails() {
        when(logRepository.findTopSearchRank(any(), any()))
                .thenThrow(new RuntimeException("mongo auth failed"));

        List<?> result = logService.searchRank(2, 3);

        assertThat(result).isEmpty();
        verifyNoInteractions(partnerRepository);
    }

    @Test
    void saveResponseLogsLoadsBenefitsOnceAndSavesDocumentsInBatch() {
        Partner firstPartner = Partner.builder().partnerId(200L).partnerName("첫 번째 제휴사").build();
        Partner secondPartner = Partner.builder().partnerId(201L).partnerName("두 번째 제휴사").build();
        Benefit firstBenefit = Benefit.builder()
                .benefitId(100L)
                .benefitName("첫 번째 혜택")
                .partner(firstPartner)
                .build();
        Benefit secondBenefit = Benefit.builder()
                .benefitId(101L)
                .benefitName("두 번째 혜택")
                .partner(secondPartner)
                .build();
        List<ResponseLogCommand> commands = List.of(
                new ResponseLogCommand("search", 100L, 200L, "/api/v1/benefits", "keyword=카페"),
                new ResponseLogCommand("search", 101L, 201L, "/api/v1/benefits", "keyword=카페")
        );
        when(benefitRepository.findAllByIdWithPartner(List.of(100L, 101L)))
                .thenReturn(List.of(firstBenefit, secondBenefit));

        logService.saveResponseLogs(7L, commands);

        verify(benefitRepository).findAllByIdWithPartner(List.of(100L, 101L));
        verify(logRepository).saveAll(argThat(documents -> {
            assertThat(documents)
                    .extracting(LogDocument::getBenefitId, LogDocument::getPartnerName)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple(100L, "첫 번째 제휴사"),
                            org.assertj.core.groups.Tuple.tuple(101L, "두 번째 제휴사")
                    );
            return true;
        }));
    }

    @Test
    void saveResponseLogsSkipsUnknownBenefits() {
        List<ResponseLogCommand> commands = List.of(
                new ResponseLogCommand("search", 999L, 200L, "/api/v1/benefits", "keyword=카페")
        );
        when(benefitRepository.findAllByIdWithPartner(List.of(999L))).thenReturn(List.of());

        logService.saveResponseLogs(7L, commands);

        verify(logRepository, never()).saveAll(any());
    }

    @Test
    void saveResponseLogsDoesNotPropagateRepositoryFailure() {
        List<ResponseLogCommand> commands = List.of(
                new ResponseLogCommand("search", 100L, 200L, "/api/v1/benefits", "keyword=카페")
        );
        when(benefitRepository.findAllByIdWithPartner(List.of(100L)))
                .thenThrow(new RuntimeException("database unavailable"));

        assertThatCode(() -> logService.saveResponseLogs(7L, commands))
                .doesNotThrowAnyException();

        verify(logRepository, never()).saveAll(any());
    }
}
