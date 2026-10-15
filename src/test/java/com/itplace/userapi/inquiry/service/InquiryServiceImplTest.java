package com.itplace.userapi.inquiry.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.itplace.userapi.inquiry.dto.request.InquiryCreateRequest;
import com.itplace.userapi.inquiry.entity.Inquiry;
import com.itplace.userapi.inquiry.repository.InquiryRepository;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class InquiryServiceImplTest {
    @Mock private InquiryRepository inquiryRepository;
    @InjectMocks private InquiryServiceImpl service;
    private static Validator validator;
    private static jakarta.validation.ValidatorFactory validatorFactory;

    @BeforeAll
    static void createValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    @Test
    void createTrimsAllFieldsBeforeSavingAndReturnsSavedInquiry() {
        when(inquiryRepository.save(any(Inquiry.class))).thenAnswer(invocation -> {
            Inquiry inquiry = invocation.getArgument(0);
            ReflectionTestUtils.setField(inquiry, "id", 41L);
            return inquiry;
        });

        var result = service.createInquiry(request("  결제  ", "  제목  ", "  내용  "));

        assertThat(result.id()).isEqualTo(41L);
        var captor = org.mockito.ArgumentCaptor.forClass(Inquiry.class);
        verify(inquiryRepository).save(captor.capture());
        assertThat(captor.getValue().getCategory()).isEqualTo("결제");
        assertThat(captor.getValue().getTitle()).isEqualTo("제목");
        assertThat(captor.getValue().getContent()).isEqualTo("내용");
    }

    @Test
    void saveFailurePropagatesWithoutRetryingOrReturningSuccess() {
        RuntimeException failure = new RuntimeException("storage unavailable");
        when(inquiryRepository.save(any(Inquiry.class))).thenThrow(failure);

        assertThatThrownBy(() -> service.createInquiry(request("문의", "제목", "내용")))
                .isSameAs(failure);
        verify(inquiryRepository).save(any(Inquiry.class));
    }

    @Test
    void validationAcceptsExactMaximumLengthsAndRejectsBlankOrOverLimitValues() {
        InquiryCreateRequest atLimits = request("가".repeat(50), "가".repeat(200), "가".repeat(4000));
        assertThat(validator.validate(atLimits)).isEmpty();

        InquiryCreateRequest invalid = request("  ", "가".repeat(201), "가".repeat(4001));
        Set<?> violations = validator.validate(invalid);
        assertThat(violations).hasSize(3); // category blank; title/content exceed limits
    }

    private InquiryCreateRequest request(String category, String title, String content) {
        InquiryCreateRequest request = new InquiryCreateRequest();
        ReflectionTestUtils.setField(request, "category", category);
        ReflectionTestUtils.setField(request, "title", title);
        ReflectionTestUtils.setField(request, "content", content);
        return request;
    }
}
