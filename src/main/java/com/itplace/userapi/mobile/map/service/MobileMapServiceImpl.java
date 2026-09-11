package com.itplace.userapi.mobile.map.service;

import com.itplace.userapi.map.dto.response.StoreDetailResponse;
import com.itplace.userapi.map.service.StoreService;
import com.itplace.userapi.mobile.map.dto.MobileMapCenterResponse;
import com.itplace.userapi.mobile.map.dto.MobileMapMarkerResponse;
import com.itplace.userapi.mobile.map.dto.MobileMapNearbyResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class MobileMapServiceImpl implements MobileMapService {
    private final StoreService storeService;

    @Override
    public MobileMapNearbyResponse findNearby(
            double lat,
            double lng,
            double userLat,
            double userLng,
            double radiusMeters,
            String carrier,
            String category,
            String keyword,
            String partnerName
    ) {
        List<StoreDetailResponse> stores = storeService.findNearbyForMobile(lat, lng, userLat, userLng,
                radiusMeters, carrier, category, keyword, partnerName);
        // StoreService가 반경과 통신사 필터를 적용한 결과를 마커 계약으로만 변환한다.
        List<MobileMapMarkerResponse> markers = stores.stream()
                .map(MobileMapMarkerResponse::from)
                .toList();

        return new MobileMapNearbyResponse(
                new MobileMapCenterResponse(lat, lng),
                radiusMeters,
                markers
        );
    }

}
