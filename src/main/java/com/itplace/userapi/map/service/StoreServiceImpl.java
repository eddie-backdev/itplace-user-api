package com.itplace.userapi.map.service;

import com.itplace.userapi.map.StoreCode;
import com.itplace.userapi.map.dto.BenefitCacheDto;
import com.itplace.userapi.map.dto.response.StoreDetailDto;
import com.itplace.userapi.map.dto.response.TierBenefitDto;
import com.itplace.userapi.map.entity.Store;
import com.itplace.userapi.map.exception.StoreKeywordException;
import com.itplace.userapi.map.repository.StoreRepository;
import com.itplace.userapi.partner.PartnerCode;
import com.itplace.userapi.partner.entity.Partner;
import com.itplace.userapi.partner.exception.PartnerNotFoundException;
import com.itplace.userapi.partner.repository.PartnerRepository;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class StoreServiceImpl implements StoreService {
    private final StoreRepository storeRepository;
    private final PartnerRepository partnerRepository;
    // [변경] BenefitRepository, TierBenefitRepository 직접 의존 제거
    // → PartnerBenefitCacheService로 대체.
    // 이유: 각 메서드마다 benefit/tierBenefit을 DB에서 직접 조회하는 방식에서,
    //       Redis 캐시를 통해 조회하는 방식으로 전환하여 DB 부하 절감.
    private final PartnerBenefitCacheService partnerBenefitCacheService;
    private final StoreSearchService storeSearchService;

    private static final int GRID_SIZE = 10;
    private static final int STORES_PER_CELL = 5;
    private static final int FINAL_LIMIT = 300;
    private static final int WIDE_RADIUS_THRESHOLD = 10000;
    private static final ExecutorService GRID_EXECUTOR = Executors.newFixedThreadPool(10);

    @PreDestroy
    public void shutdownExecutor() {
        GRID_EXECUTOR.shutdown();
    }

    @Override
    @Transactional(readOnly = true)
    public List<StoreDetailDto> findNearby(double lat, double lng, double radiusMeters, double userLat,
                                           double userLng) {
        List<Long> allStoreIds;

        if (radiusMeters <= WIDE_RADIUS_THRESHOLD) {
            allStoreIds = findNearbyWithSingleQuery(lat, lng, radiusMeters);
        } else {
            allStoreIds = findNearbyWithGridSampling(lat, lng, radiusMeters);
        }

        if (allStoreIds.isEmpty()) {
            return Collections.emptyList();
        }

        Collections.shuffle(allStoreIds);
        List<Long> limitedStoreIds = allStoreIds.subList(0, Math.min(allStoreIds.size(), FINAL_LIMIT));
        List<Store> limitedStores = storeRepository.findAllById(limitedStoreIds);

        List<Long> partnerIds = limitedStores.stream()
                .map(store -> store.getPartner().getPartnerId())
                .distinct()
                .toList();

        // [변경] 기존: benefitRepository.findAllByPartner_PartnerIdIn + tierBenefitRepository.findAllByBenefitIn 으로
        //        allBenefits → partnerToBenefitsMap, allTierBenefits → benefitToTiersMap 두 개의 맵을 구성.
        // 변경 후: partnerBenefitCacheService.getBenefits(partnerId) 호출 한 번으로 혜택+등급혜택 정보를
        //         BenefitCacheDto에 묶어 가져옴. Redis에 캐싱되어 있으면 DB 조회 없이 반환.
        Map<Long, List<BenefitCacheDto>> partnerToBenefitsMap = partnerIds.stream()
                .collect(Collectors.toMap(id -> id, partnerBenefitCacheService::getBenefits));

        return limitedStores.stream()
                .map(store -> {
                    Partner partner = store.getPartner();
                    List<BenefitCacheDto> finalBenefits = selectBenefits(
                            partnerToBenefitsMap.getOrDefault(partner.getPartnerId(), List.of()),
                            store.getStoreName()
                    );
                    // [변경] 기존: benefitToTiersMap에서 benefitId로 TierBenefit을 조회 후 TierBenefitDto로 변환.
                    // 변경 후: BenefitCacheDto 안에 TierBenefitDto 리스트가 이미 포함되어 있으므로
                    //         getTierBenefits()로 바로 꺼내서 flatMap.
                    List<TierBenefitDto> tierBenefitDtos = finalBenefits.stream()
                            .flatMap(b -> b.getTierBenefits().stream())
                            .toList();
                    double distance = calculateDistance(userLat, userLng,
                            store.getLocation().getY(), store.getLocation().getX());
                    return StoreDetailDto.of(store, partner, tierBenefitDtos, distance);
                })
                .sorted(Comparator.comparing(StoreDetailDto::getDistance))
                .toList();
    }

    private List<Long> findNearbyWithSingleQuery(double lat, double lng, double radiusMeters) {
        double earthRadius = 6378137.0;
        double dLat = radiusMeters / earthRadius;
        double dLng = radiusMeters / (earthRadius * Math.cos(Math.toRadians(lat)));

        double minLat = lat - Math.toDegrees(dLat);
        double maxLat = lat + Math.toDegrees(dLat);
        double minLng = lng - Math.toDegrees(dLng);
        double maxLng = lng + Math.toDegrees(dLng);

        return storeRepository.findStoreIdsInRadius(lat, lng, radiusMeters, minLat, maxLat, minLng, maxLng);
    }

    private List<Long> findNearbyWithGridSampling(double lat, double lng, double radiusMeters) {
        double earthRadius = 6378137.0;
        double dLat = radiusMeters / earthRadius;
        double dLng = radiusMeters / (earthRadius * Math.cos(Math.toRadians(lat)));

        double minLat = lat - Math.toDegrees(dLat);
        double maxLat = lat + Math.toDegrees(dLat);
        double minLng = lng - Math.toDegrees(dLng);
        double maxLng = lng + Math.toDegrees(dLng);

        double latStep = (maxLat - minLat) / GRID_SIZE;
        double lngStep = (maxLng - minLng) / GRID_SIZE;

        List<CompletableFuture<List<Long>>> futures = new ArrayList<>();

        for (int i = 0; i < GRID_SIZE; i++) {
            for (int j = 0; j < GRID_SIZE; j++) {
                double cellMinLat = minLat + i * latStep;
                double cellMaxLat = cellMinLat + latStep;
                double cellMinLng = minLng + j * lngStep;
                double cellMaxLng = cellMinLng + lngStep;

                futures.add(CompletableFuture.supplyAsync(() ->
                        storeRepository.findRandomStoreIdsInBounds(cellMinLat, cellMaxLat, cellMinLng, cellMaxLng, STORES_PER_CELL),
                        GRID_EXECUTOR
                ));
            }
        }

        return futures.stream()
                .map(CompletableFuture::join)
                .flatMap(List::stream)
                .distinct()
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<StoreDetailDto> findNearbyByCategory(double lat, double lng, double radiusMeters, String category,
                                                     double userLat, double userLng) {
        if (category == null || category.isBlank() || category.equalsIgnoreCase("전체")) {
            return findNearby(lat, lng, radiusMeters, userLat, userLng);
        }

        log.info("카테고리 기반 반경 검색 실행: {}, 반경: {}m", category, radiusMeters);

        List<Store> limitedStores = storeRepository.findRandomStoresByCategory(category, lat, lng, radiusMeters, FINAL_LIMIT);

        if (limitedStores.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> partnerIds = limitedStores.stream()
                .map(store -> store.getPartner().getPartnerId())
                .distinct()
                .toList();

        // [변경] findNearby와 동일하게 benefitRepository/tierBenefitRepository 직접 조회 제거.
        // 기존: allBenefits, allTierBenefits를 각각 DB에서 일괄 조회 후 두 개의 맵으로 구성.
        // 변경 후: 캐시 서비스 호출 한 번으로 파트너별 혜택 맵 구성.
        Map<Long, List<BenefitCacheDto>> partnerToBenefitsMap = partnerIds.stream()
                .collect(Collectors.toMap(id -> id, partnerBenefitCacheService::getBenefits));

        return limitedStores.stream()
                .map(store -> {
                    Partner partner = store.getPartner();
                    List<BenefitCacheDto> finalBenefits = selectBenefits(
                            partnerToBenefitsMap.getOrDefault(partner.getPartnerId(), List.of()),
                            store.getStoreName()
                    );
                    // [변경] 기존: benefitToTiersMap에서 조회 후 TierBenefitDto로 변환.
                    // 변경 후: BenefitCacheDto.getTierBenefits()로 이미 변환된 DTO 리스트를 바로 사용.
                    List<TierBenefitDto> tierBenefitDtos = finalBenefits.stream()
                            .flatMap(b -> b.getTierBenefits().stream())
                            .toList();
                    double distance = calculateDistance(userLat, userLng,
                            store.getLocation().getY(), store.getLocation().getX());
                    return StoreDetailDto.of(store, partner, tierBenefitDtos, distance);
                })
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<StoreDetailDto> findNearbyByKeyword(double lat, double lng, String category,
                                                    String keyword, double userLat, double userLng) {
        if (keyword == null || keyword.isBlank()) {
            throw new StoreKeywordException(StoreCode.KEYWORD_REQUEST);
        }

        if (category != null && (category.isBlank() || category.equalsIgnoreCase("전체"))) {
            category = null;
        } else if (category != null) {
            category = category.trim();
        }

        // 1단계: ES nori 형태소 분석으로 브랜드 매치 / 매장명 매치 분리
        StoreSearchResult searchResult = storeSearchService.searchByKeyword(keyword, category);
        if (searchResult.isEmpty()) {
            return Collections.emptyList();
        }

        // 2단계: DB에서 매장 데이터 일괄 로드
        List<Long> allIds = Stream.concat(
                searchResult.brandMatchIds().stream(),
                searchResult.nameMatchIds().stream()
        ).toList();

        List<Store> allStores = storeRepository.findAllById(allIds);
        if (allStores.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> partnerIds = allStores.stream()
                .map(store -> store.getPartner().getPartnerId())
                .distinct()
                .toList();

        Map<Long, List<BenefitCacheDto>> partnerToBenefitsMap = partnerIds.stream()
                .collect(Collectors.toMap(id -> id, partnerBenefitCacheService::getBenefits));

        // storeId → DTO 맵 구성
        Map<Long, StoreDetailDto> dtoMap = allStores.stream()
                .collect(Collectors.toMap(
                        store -> store.getStoreId(),
                        store -> {
                            Partner partner = store.getPartner();
                            double storeLat = store.getLocation().getY();
                            double storeLng = store.getLocation().getX();
                            double distance = userLat == 0 || userLng == 0
                                    ? 0 : calculateDistance(userLat, userLng, storeLat, storeLng);
                            List<BenefitCacheDto> finalBenefits = selectBenefits(
                                    partnerToBenefitsMap.getOrDefault(partner.getPartnerId(), List.of()),
                                    store.getStoreName()
                            );
                            List<TierBenefitDto> tierBenefitDtos = finalBenefits.stream()
                                    .flatMap(b -> b.getTierBenefits().stream())
                                    .toList();
                            return StoreDetailDto.of(store, partner, tierBenefitDtos, distance);
                        }
                ));

        // 브랜드 매치(partnerName) 그룹을 거리순 정렬 후 우선 노출
        // 매장명 매치(storeName/business) 그룹은 거리순 정렬 후 후순위 노출
        return Stream.concat(
                searchResult.brandMatchIds().stream()
                        .map(dtoMap::get)
                        .filter(Objects::nonNull)
                        .sorted(Comparator.comparing(StoreDetailDto::getDistance)),
                searchResult.nameMatchIds().stream()
                        .map(dtoMap::get)
                        .filter(Objects::nonNull)
                        .sorted(Comparator.comparing(StoreDetailDto::getDistance))
        ).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<StoreDetailDto> findNearbyByPartnerName(double lat, double lng, String partnerName, double userLat,
                                                        double userLng) {
        if (partnerName == null || partnerName.isBlank()) {
            throw new StoreKeywordException(StoreCode.PARTNERNAME_REQUEST);
        }

        Partner partner = partnerRepository.findByPartnerName(partnerName)
                .orElseThrow(() -> new PartnerNotFoundException(PartnerCode.PARTNER_NOT_FOUND));

        List<Store> stores = storeRepository.searchNearbyStoresByPartnerId(lng, lat, partner.getPartnerId());

        // [변경] 기존: 루프 내부에서 매 매장(store)마다 benefitRepository.findAllByPartner_PartnerId()와
        //        tierBenefitRepository.findAllByBenefit_BenefitId()를 반복 호출 → 매장 수만큼 DB 쿼리 발생(N+1).
        // 변경 후: 루프 밖에서 캐시 서비스를 통해 해당 파트너의 혜택을 한 번만 조회.
        //         이후 루프에서는 DB 호출 없이 캐시 결과를 재사용.
        List<BenefitCacheDto> partnerBenefits = partnerBenefitCacheService.getBenefits(partner.getPartnerId());

        return stores.stream()
                .map(store -> {
                    double distance = calculateDistance(userLat, userLng,
                            store.getLocation().getY(), store.getLocation().getX());
                    List<BenefitCacheDto> finalBenefits = selectBenefits(partnerBenefits, store.getStoreName());
                    // [변경] 기존: tierBenefitRepository를 루프 내에서 직접 호출하여 TierBenefitDto 생성.
                    // 변경 후: BenefitCacheDto.getTierBenefits()에서 바로 꺼냄.
                    List<TierBenefitDto> tierBenefitDtos = finalBenefits.stream()
                            .flatMap(b -> b.getTierBenefits().stream())
                            .toList();
                    return StoreDetailDto.of(store, partner, tierBenefitDtos, distance);
                })
                .toList();
    }

    private double calculateDistance(double userLat, double userLng, double storeLat, double storeLng) {
        final int earthRadius = 6378137;

        double dLat = Math.toRadians(storeLat - userLat);
        double dLng = Math.toRadians(storeLng - userLng);

        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(userLat))
                * Math.cos(Math.toRadians(storeLat))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

        double d = earthRadius * c * 0.001;
        return Math.round(d * 10) / 10.0;
    }

    // [변경] 파라미터 타입 List<Benefit> → List<BenefitCacheDto>로 변경.
    // 이유: 캐시 서비스 도입으로 Benefit 엔티티 대신 BenefitCacheDto를 사용하게 됨.
    //       로직(오프라인 우선 선택, 매장명 매칭)은 동일하며 타입만 교체.
    private List<BenefitCacheDto> selectBenefits(List<BenefitCacheDto> benefits, String storeName) {
        if (benefits == null || benefits.isEmpty()) {
            return Collections.emptyList();
        }

        return benefits.stream()
                .filter(b -> b.getBenefitName().contains("오프라인"))
                .findFirst()
                .map(List::of)
                .orElseGet(() -> {
                    if (benefits.size() >= 3) {
                        List<BenefitCacheDto> matched = benefits.stream()
                                .filter(b -> b.getBenefitName().equals(storeName))
                                .toList();
                        if (!matched.isEmpty()) {
                            return matched;
                        }
                    }
                    return benefits;
                });
    }
}
