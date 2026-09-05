package com.itplace.userapi.map.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.itplace.userapi.map.repository.StoreRepository;
import com.itplace.userapi.map.repository.projection.StorePreviewProjection;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@ExtendWith(MockitoExtension.class)
class StorePreviewQueryServiceTest {

    @Mock
    private StoreRepository storeRepository;

    @Mock
    private StorePreviewProjection projection;

    @InjectMocks
    private StorePreviewQueryService queryService;

    @Test
    void findStorePreviewsInViewReturnsDetachedSnapshot() {
        when(storeRepository.findStorePreviewsInView(
                37.49, 37.52, 126.99, 127.02, 37.505, 127.005, null, 300
        )).thenReturn(List.of(projection));
        when(projection.getStoreId()).thenReturn(1L);
        when(projection.getPartnerId()).thenReturn(10L);
        when(projection.getStoreName()).thenReturn("GS25 강남점");
        when(projection.getBusiness()).thenReturn("편의점");
        when(projection.getPartnerName()).thenReturn("GS25");
        when(projection.getCategory()).thenReturn("생활/편의");
        when(projection.getLatitude()).thenReturn(37.501);
        when(projection.getLongitude()).thenReturn(127.001);
        when(projection.getHasCoupon()).thenReturn(true);

        List<StorePreviewProjection> result = queryService.findStorePreviewsInView(
                37.49, 37.52, 126.99, 127.02, 37.505, 127.005, null, 300
        );

        assertThat(result).singleElement()
                .isNotSameAs(projection)
                .satisfies(snapshot -> {
                    assertThat(snapshot.getStoreId()).isEqualTo(1L);
                    assertThat(snapshot.getPartnerId()).isEqualTo(10L);
                    assertThat(snapshot.getStoreName()).isEqualTo("GS25 강남점");
                    assertThat(snapshot.getBusiness()).isEqualTo("편의점");
                    assertThat(snapshot.getPartnerName()).isEqualTo("GS25");
                    assertThat(snapshot.getCategory()).isEqualTo("생활/편의");
                    assertThat(snapshot.getLatitude()).isEqualTo(37.501);
                    assertThat(snapshot.getLongitude()).isEqualTo(127.001);
                    assertThat(snapshot.getHasCoupon()).isTrue();
                });
        verify(storeRepository).findStorePreviewsInView(
                37.49, 37.52, 126.99, 127.02, 37.505, 127.005, null, 300
        );
    }

    @Test
    void previewOrchestrationRunsOutsideTransactionAndDatabaseQueryUsesReadOnlyTransaction() throws Exception {
        Method orchestrationMethod = StoreServiceImpl.class.getMethod(
                "findStoresInViewPreviews",
                double.class,
                double.class,
                double.class,
                double.class,
                String.class,
                double.class,
                double.class,
                int.class,
                boolean.class
        );
        Method queryMethod = StorePreviewQueryService.class.getMethod(
                "findStorePreviewsInView",
                double.class,
                double.class,
                double.class,
                double.class,
                double.class,
                double.class,
                String.class,
                int.class
        );

        Transactional orchestrationTransaction = orchestrationMethod.getAnnotation(Transactional.class);
        Transactional queryTransaction = queryMethod.getAnnotation(Transactional.class);

        assertThat(orchestrationTransaction.propagation()).isEqualTo(Propagation.NOT_SUPPORTED);
        assertThat(queryTransaction.readOnly()).isTrue();
        assertThat(queryTransaction.propagation()).isEqualTo(Propagation.REQUIRED);

        Method batchMethod = StoreServiceImpl.class.getMethod(
                "findStoresInViewPreviewBatch",
                double.class,
                double.class,
                double.class,
                double.class,
                String.class,
                int.class
        );
        Transactional batchTransaction = batchMethod.getAnnotation(Transactional.class);
        assertThat(batchTransaction.propagation()).isEqualTo(Propagation.NOT_SUPPORTED);
    }
}
