package com.itplace.userapi.map.service;

import com.itplace.userapi.map.repository.StoreRepository;
import com.itplace.userapi.map.repository.projection.StoreClusterProjection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class StoreClusterQueryService {

    private final StoreRepository storeRepository;

    /**
     * 클러스터 cache miss에서 실제 DB 조회 구간만 읽기 트랜잭션으로 실행한다.
     *
     * <p>캐시의 동일 key 동기화를 기다리는 요청이 DB 커넥션을 점유하지 않도록
     * 캐시 오케스트레이션과 트랜잭션 경계를 분리한다.</p>
     */
    @Transactional(readOnly = true)
    public List<StoreClusterProjection> findStoreClustersInView(
            double minLat,
            double maxLat,
            double minLng,
            double maxLng,
            String category,
            int mapLevel,
            String administrativeUnitType
    ) {
        return storeRepository.findStoreClustersInView(
                        minLat,
                        maxLat,
                        minLng,
                        maxLng,
                        category,
                        mapLevel,
                        administrativeUnitType
                ).stream()
                .<StoreClusterProjection>map(StoreClusterSnapshot::from)
                .toList();
    }

    private record StoreClusterSnapshot(
            String clusterId,
            String category,
            String administrativeUnitType,
            String administrativeUnitName,
            Double latitude,
            Double longitude,
            Long count
    ) implements StoreClusterProjection {

        private static StoreClusterSnapshot from(StoreClusterProjection projection) {
            return new StoreClusterSnapshot(
                    projection.getClusterId(),
                    projection.getCategory(),
                    projection.getAdministrativeUnitType(),
                    projection.getAdministrativeUnitName(),
                    projection.getLatitude(),
                    projection.getLongitude(),
                    projection.getCount()
            );
        }

        @Override
        public String getClusterId() {
            return clusterId;
        }

        @Override
        public String getCategory() {
            return category;
        }

        @Override
        public String getAdministrativeUnitType() {
            return administrativeUnitType;
        }

        @Override
        public String getAdministrativeUnitName() {
            return administrativeUnitName;
        }

        @Override
        public Double getLatitude() {
            return latitude;
        }

        @Override
        public Double getLongitude() {
            return longitude;
        }

        @Override
        public Long getCount() {
            return count;
        }
    }
}
