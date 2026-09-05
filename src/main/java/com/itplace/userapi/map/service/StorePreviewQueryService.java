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
     * <p>JPA projection을 불변 스냅샷으로 복사해 반환하므로 트랜잭션이 종료된 뒤에도
     * Redis 혜택 조회와 응답 조립 과정에서 DB 커넥션을 점유하지 않는다.</p>
     */
    @Transactional(readOnly = true)
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

        private static StorePreviewSnapshot from(StorePreviewProjection projection) {
            return new StorePreviewSnapshot(
                    projection.getStoreId(),
                    projection.getPartnerId(),
                    projection.getStoreName(),
                    projection.getBusiness(),
                    projection.getPartnerName(),
                    projection.getCategory(),
                    projection.getImage(),
                    projection.getLatitude(),
                    projection.getLongitude(),
                    projection.getAddress(),
                    projection.getRoadName(),
                    projection.getRoadAddress(),
                    projection.getPostCode(),
                    projection.getHasCoupon()
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
