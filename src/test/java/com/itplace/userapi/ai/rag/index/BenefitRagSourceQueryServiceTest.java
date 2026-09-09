package com.itplace.userapi.ai.rag.index;

import static org.assertj.core.api.Assertions.assertThat;
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

    @Mock
    private com.itplace.userapi.benefit.repository.BenefitCarrierPolicyRepository policyRepository;
    @Mock
    private com.itplace.userapi.benefit.repository.CarrierTierBenefitRepository tierRepository;

    @InjectMocks
    private BenefitRagSourceQueryService queryService;

    @Test
    void sourceQueriesPoliciesInBoundedBatches() {
        List<Benefit> benefits = java.util.stream.LongStream.rangeClosed(1, 401)
                .mapToObj(id -> Benefit.builder().benefitId(id).build()).toList();
        when(benefitRepository.findAllWithPartnerAndTierBenefits()).thenReturn(benefits);
        assertThat(queryService.loadSourceSnapshot().scannedBenefits()).isEqualTo(401);
        org.mockito.ArgumentCaptor<List<Benefit>> batches = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(policyRepository, org.mockito.Mockito.times(3)).findAllByBenefitIn(batches.capture());
        assertThat(batches.getAllValues()).extracting(List::size).containsExactly(200, 200, 1);
        org.mockito.Mockito.verifyNoInteractions(tierRepository);
    }

    @Test
    void loadSourceSnapshot_buildsDetachedDocumentsInsideReadOnlyTransaction() throws Exception {
        Benefit first = Benefit.builder().benefitId(1L).build();
        Benefit second = Benefit.builder().benefitId(2L).build();
        BenefitRagDocumentBuilder.PendingBenefitDocument pending =
                new BenefitRagDocumentBuilder.PendingBenefitDocument(
                        BenefitDocument.builder().documentId("benefit:1").build(),
                        "search text"
                );
        when(benefitRepository.findAllWithPartnerAndTierBenefits()).thenReturn(List.of(first, second));
        when(documentBuilder.buildPendingDocuments(first, List.of(), List.of())).thenReturn(List.of(pending));
        when(documentBuilder.buildPendingDocuments(second, List.of(), List.of())).thenReturn(List.of());

        BenefitRagSourceQueryService.SourceSnapshot result = queryService.loadSourceSnapshot();

        assertThat(result.scannedBenefits()).isEqualTo(2);
        assertThat(result.pendingDocuments()).containsExactly(pending);
        verify(documentBuilder).buildPendingDocuments(first, List.of(), List.of());
        verify(documentBuilder).buildPendingDocuments(second, List.of(), List.of());

        Method method = BenefitRagSourceQueryService.class.getMethod("loadSourceSnapshot");
        Transactional transactional = method.getAnnotation(Transactional.class);
        assertThat(transactional).isNotNull();
        assertThat(transactional.readOnly()).isTrue();
    }
}
