package com.itplace.userapi.log.interceptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import com.itplace.userapi.log.service.LogService;
import com.itplace.userapi.log.service.LogServiceImpl;
import com.itplace.userapi.log.repository.LogRepository;
import com.itplace.userapi.log.dto.ResponseLogCommand;
import com.itplace.userapi.benefit.repository.BenefitRepository;
import com.itplace.userapi.benefit.entity.Benefit;
import com.itplace.userapi.partner.repository.PartnerRepository;
import com.itplace.userapi.security.auth.common.PrincipalDetails;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class LoggingInterceptorTest {
    @Test
    void clickUsesIsolatedBatchLoggerAndToleratesDatabaseOrSubmissionFailure() {
        PrincipalDetails principal = () -> 7L;
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null));
        try {
            var mongo = mock(LogRepository.class);
            var benefits = mock(BenefitRepository.class);
            when(benefits.findAllByIdWithPartner(List.of(100L)))
                    .thenReturn(List.of(Benefit.builder().benefitId(100L).build()));
            when(mongo.saveAll(any())).thenThrow(new IllegalStateException("Mongo down"));
            var request = new MockHttpServletRequest("GET", "/api/v1/benefits/100");
            var response = new MockHttpServletResponse();
            var logger = new LogServiceImpl(mongo, benefits, mock(PartnerRepository.class));
            assertThat(new LoggingInterceptor(logger).preHandle(request, response, new Object())).isTrue();
            verify(mongo).saveAll(any());
            var unavailable = mock(LogService.class);
            doThrow(new IllegalStateException("executor stopped")).when(unavailable).saveResponseLogs(any(), any());
            assertThat(new LoggingInterceptor(unavailable).preHandle(request, response, new Object())).isTrue();
            verify(unavailable).saveResponseLogs(7L, List.of(new ResponseLogCommand("click", 100L, null, request.getRequestURI(), null)));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
