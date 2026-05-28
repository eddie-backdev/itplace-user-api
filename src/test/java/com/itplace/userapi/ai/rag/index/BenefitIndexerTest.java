package com.itplace.userapi.ai.rag.index;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class BenefitIndexerTest {

    @Mock
    private BenefitRagSyncService benefitRagSyncService;

    @Test
    void run_doesNotStartBenefitSyncWhenSeedIsDisabled() throws Exception {
        BenefitIndexer indexer = new BenefitIndexer(benefitRagSyncService);
        ReflectionTestUtils.setField(indexer, "seedEnabled", false);

        indexer.run(null);

        verify(benefitRagSyncService, never()).syncAll();
    }
}
