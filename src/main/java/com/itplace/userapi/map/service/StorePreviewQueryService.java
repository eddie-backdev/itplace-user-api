package com.itplace.userapi.map.service;

import com.itplace.userapi.map.repository.StoreRepository;
import com.itplace.userapi.map.repository.projection.StorePreviewProjection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class StorePreviewQueryService {

    private final StoreRepository storeRepository;

    /**
     * 지도 미리보기 조회에 필요한 DB 작업만 짧은 읽기 트랜잭션으로 실행한다.
     *
     * <p>JDBC scalar row를 프록시 없이 불변 스냅샷으로 매핑하므로 트랜잭션이 종료된 뒤에도
     * Redis 혜택 조회와 응답 조립 과정에서 DB 커넥션을 점유하지 않는다.</p>
     */
    @Transactional(readOnly = true, timeout = 5)
    public List<StorePreviewProjection> findStorePreviewsInView(
            double minLat,
            double maxLat,
            double minLng,
            double maxLng,
            double centerLat,
            double centerLng,
            String category,
            int limit
    ) {
        return storeRepository.findStorePreviewsInView(
                        minLat,
                        maxLat,
                        minLng,
                        maxLng,
                        centerLat,
                        centerLng,
                        category,
                        limit
                ).stream()
                .<StorePreviewProjection>map(StorePreviewSnapshot::from)
                .toList();
    }

    private record StorePreviewSnapshot(
            Long storeId,
            Long partnerId,
            String storeName,
            String business,
            String partnerName,
            String category,
            String image,
            Double latitude,
            Double longitude,
            String address,
            String roadName,
            String roadAddress,
            String postCode,
            Boolean hasCoupon
    ) implements StorePreviewProjection {

        private static StorePreviewSnapshot from(Object[] row) {
            return new StorePreviewSnapshot(
                    ((Number) row[0]).longValue(),
                    ((Number) row[1]).longValue(),
                    (String) row[2],
                    (String) row[3],
                    (String) row[4],
                    (String) row[5],
                    (String) row[6],
                    ((Number) row[7]).doubleValue(),
                    ((Number) row[8]).doubleValue(),
                    (String) row[9],
                    (String) row[10],
                    (String) row[11],
                    (String) row[12],
                    (Boolean) row[13]
            );
        }

        @Override
        public Long getStoreId() {
            return storeId;
        }

        @Override
        public Long getPartnerId() {
            return partnerId;
        }

        @Override
        public String getStoreName() {
            return storeName;
        }

        @Override
        public String getBusiness() {
            return business;
        }

        @Override
        public String getPartnerName() {
            return partnerName;
        }

        @Override
        public String getCategory() {
            return category;
        }

        @Override
        public String getImage() {
            return image;
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
        public String getAddress() {
            return address;
        }

        @Override
        public String getRoadName() {
            return roadName;
        }

        @Override
        public String getRoadAddress() {
            return roadAddress;
        }

        @Override
        public String getPostCode() {
            return postCode;
        }

        @Override
        public Boolean getHasCoupon() {
            return hasCoupon;
        }
    }
}
