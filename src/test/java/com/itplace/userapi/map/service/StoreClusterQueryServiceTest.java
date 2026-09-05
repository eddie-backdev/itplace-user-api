package com.itplace.userapi.map.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.itplace.userapi.map.repository.StoreRepository;
import com.itplace.userapi.map.repository.projection.StoreClusterProjection;
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
class StoreClusterQueryServiceTest {

    @Mock
    private StoreRepository storeRepository;

    @Mock
    private StoreClusterProjection projection;

    @InjectMocks
    private StoreClusterQueryService queryService;

    @Test
    void findStoreClustersInViewReturnsDetachedSnapshot() {
        when(storeRepository.findStoreClustersInView(
                37.49, 37.52, 126.99, 127.02, null, 7, "TOWN"
        )).thenReturn(List.of(projection));
        when(projection.getClusterId()).thenReturn("a:7:TOWN:one");
        when(projection.getCategory()).thenReturn("전체");
        when(projection.getAdministrativeUnitType()).thenReturn("TOWN");
        when(projection.getAdministrativeUnitName()).thenReturn("강남구");
        when(projection.getLatitude()).thenReturn(37.501);
        when(projection.getLongitude()).thenReturn(127.001);
        when(projection.getCount()).thenReturn(44L);

        List<StoreClusterProjection> result = queryService.findStoreClustersInView(
                37.49, 37.52, 126.99, 127.02, null, 7, "TOWN"
        );

        assertThat(result).singleElement()
                .isNotSameAs(projection)
                .satisfies(snapshot -> {
                    assertThat(snapshot.getClusterId()).isEqualTo("a:7:TOWN:one");
                    assertThat(snapshot.getAdministrativeUnitType()).isEqualTo("TOWN");
                    assertThat(snapshot.getAdministrativeUnitName()).isEqualTo("강남구");
                    assertThat(snapshot.getCount()).isEqualTo(44L);
                });
        verify(storeRepository).findStoreClustersInView(
                37.49, 37.52, 126.99, 127.02, null, 7, "TOWN"
        );
    }

    @Test
    void cacheOrchestrationRunsWithoutTransactionAndQueryUsesReadOnlyTransaction() throws Exception {
        Method orchestrationMethod = StoreServiceImpl.class.getMethod(
                "findStoreClustersInView",
                double.class,
                double.class,
                double.class,
                double.class,
                String.class,
                int.class
        );
        Method queryMethod = StoreClusterQueryService.class.getMethod(
                "findStoreClustersInView",
                double.class,
                double.class,
                double.class,
                double.class,
                String.class,
                int.class,
                String.class
        );

        Transactional orchestrationTransaction = orchestrationMethod.getAnnotation(Transactional.class);
        Transactional queryTransaction = queryMethod.getAnnotation(Transactional.class);

        assertThat(orchestrationTransaction.propagation()).isEqualTo(Propagation.NOT_SUPPORTED);
        assertThat(queryTransaction.readOnly()).isTrue();
        assertThat(queryTransaction.propagation()).isEqualTo(Propagation.REQUIRED);
    }
}
