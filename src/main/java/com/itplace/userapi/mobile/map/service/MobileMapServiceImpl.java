package com.itplace.userapi.mobile.map.service;

import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.map.dto.response.StoreDetailResponse;
import com.itplace.userapi.map.dto.response.TierBenefitDto;
import com.itplace.userapi.map.service.StoreService;
import com.itplace.userapi.mobile.map.dto.MobileMapCenterResponse;
import com.itplace.userapi.mobile.map.dto.MobileMapMarkerResponse;
import com.itplace.userapi.mobile.map.dto.MobileMapNearbyResponse;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
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
        List<StoreDetailResponse> stores = findStores(
                lat,
                lng,
                userLat,
                userLng,
                radiusMeters,
                category,
                keyword,
                partnerName
        );
        Carrier carrierFilter = parseCarrier(carrier);
        List<MobileMapMarkerResponse> markers = stores.stream()
                .filter(store -> isWithinRadius(store, lat, lng, radiusMeters))
                .filter(store -> matchesCarrier(store, carrierFilter))
                .map(store -> MobileMapMarkerResponse.from(store, carrierFilter))
                .toList();

        return new MobileMapNearbyResponse(
                new MobileMapCenterResponse(lat, lng),
                radiusMeters,
                markers
        );
    }

    private List<StoreDetailResponse> findStores(
            double lat,
            double lng,
            double userLat,
            double userLng,
            double radiusMeters,
            String category,
            String keyword,
            String partnerName
    ) {
        if (partnerName != null && !partnerName.isBlank()) {
            return storeService.findNearbyByPartnerName(lat, lng, partnerName.trim(), userLat, userLng);
        }
        if (keyword != null && !keyword.isBlank()) {
            return storeService.findNearbyByKeyword(
                    lat,
                    lng,
                    normalizeCategory(category),
                    keyword.trim(),
                    userLat,
                    userLng
            );
        }
        return storeService.findNearbyDistributedForMap(
                lat,
                lng,
                radiusMeters,
                normalizeCategory(category),
                userLat,
                userLng
        );
    }

    private String normalizeCategory(String category) {
        if (category == null || category.isBlank() || category.equalsIgnoreCase("전체")) {
            return null;
        }
        return category.trim();
    }

    private boolean isWithinRadius(
            StoreDetailResponse store,
            double centerLat,
            double centerLng,
            double radiusMeters
    ) {
        double storeLat = store.getStore().getLatitude();
        double storeLng = store.getStore().getLongitude();
        double latDistance = Math.toRadians(storeLat - centerLat);
        double lngDistance = Math.toRadians(storeLng - centerLng);
        double haversine = Math.sin(latDistance / 2) * Math.sin(latDistance / 2)
                + Math.cos(Math.toRadians(centerLat))
                * Math.cos(Math.toRadians(storeLat))
                * Math.sin(lngDistance / 2)
                * Math.sin(lngDistance / 2);
        double normalizedHaversine = Math.max(0, Math.min(1, haversine));
        double distanceMeters = 6_378_137.0 * 2 * Math.atan2(
                Math.sqrt(normalizedHaversine),
                Math.sqrt(1 - normalizedHaversine)
        );
        return distanceMeters <= radiusMeters;
    }

    private Carrier parseCarrier(String carrier) {
        if (carrier == null || carrier.isBlank()) {
            return null;
        }
        try {
            return Carrier.valueOf(carrier.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private boolean matchesCarrier(StoreDetailResponse store, Carrier carrier) {
        if (carrier == null) {
            return true;
        }
        List<TierBenefitDto> tierBenefits = store.getTierBenefit();
        if (tierBenefits == null || tierBenefits.isEmpty()) {
            return true;
        }
        return tierBenefits.stream()
                .map(TierBenefitDto::getCarrier)
                .anyMatch(value -> value == null || value == carrier);
    }
}
