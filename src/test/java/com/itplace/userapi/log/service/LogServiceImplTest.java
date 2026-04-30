package com.itplace.userapi.log.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.itplace.userapi.benefit.repository.BenefitRepository;
import com.itplace.userapi.log.repository.LogRepository;
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
}
