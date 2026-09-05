package com.itplace.userapi.ai.rag.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.itplace.userapi.ai.rag.document.BenefitDocument;
import com.itplace.userapi.benefit.entity.Benefit;
import com.itplace.userapi.benefit.repository.BenefitRepository;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

@ExtendWith(MockitoExtension.class)
class BenefitRagSourceQueryServiceTest {

    @Mock
    private BenefitRepository benefitRepository;

    @Mock
    private BenefitRagDocumentBuilder documentBuilder;

    @InjectMocks
    private BenefitRagSourceQueryService queryService;

    @Test
    void loadSourceSnapshot_buildsDetachedDocumentsInsideReadOnlyTransaction() throws Exception {
        Benefit first = mock(Benefit.class);
        Benefit second = mock(Benefit.class);
        BenefitRagDocumentBuilder.PendingBenefitDocument pending =
                new BenefitRagDocumentBuilder.PendingBenefitDocument(
                        BenefitDocument.builder().documentId("benefit:1").build(),
                        "search text"
                );
        when(benefitRepository.findAllWithPartnerAndTierBenefits()).thenReturn(List.of(first, second));
        when(documentBuilder.buildPendingDocuments(first)).thenReturn(List.of(pending));
        when(documentBuilder.buildPendingDocuments(second)).thenReturn(List.of());

        BenefitRagSourceQueryService.SourceSnapshot result = queryService.loadSourceSnapshot();

        assertThat(result.scannedBenefits()).isEqualTo(2);
        assertThat(result.pendingDocuments()).containsExactly(pending);
        verify(documentBuilder).buildPendingDocuments(first);
        verify(documentBuilder).buildPendingDocuments(second);

        Method method = BenefitRagSourceQueryService.class.getMethod("loadSourceSnapshot");
        Transactional transactional = method.getAnnotation(Transactional.class);
        assertThat(transactional).isNotNull();
        assertThat(transactional.readOnly()).isTrue();
    }
}
