package com.itplace.userapi.map.service;

import com.itplace.userapi.map.StoreCode;
import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.map.dto.BenefitCacheDto;
import com.itplace.userapi.map.dto.response.MapStoreClusterResponse;
import com.itplace.userapi.map.dto.response.MapStorePreviewBatchResponse;
import com.itplace.userapi.map.dto.response.MapStorePreviewResponse;
import com.itplace.userapi.map.dto.response.StoreDetailResponse;
import com.itplace.userapi.map.dto.response.TierBenefitDto;
import com.itplace.userapi.map.entity.Store;
import com.itplace.userapi.map.exception.StoreKeywordException;
import com.itplace.userapi.map.repository.StoreRepository;
import com.itplace.userapi.map.repository.projection.StoreClusterProjection;
import com.itplace.userapi.map.repository.projection.StorePreviewProjection;
import com.itplace.userapi.partner.PartnerCode;
import com.itplace.userapi.partner.entity.Partner;
import com.itplace.userapi.partner.exception.PartnerNotFoundException;
import com.itplace.userapi.partner.repository.PartnerRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.NOT_SUPPORTED)
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
    private final StoreClusterCacheService storeClusterCacheService;
    private final StoreClusterQueryService storeClusterQueryService;
    private final MapClusterSnapshotCache mapClusterSnapshotCache;
    private final StorePreviewQueryService storePreviewQueryService;

    private static final int DISTRIBUTED_GRID_SIZE = 5;
    private static final int FINAL_LIMIT = 300;
    private static final int CANDIDATE_FETCH_MULTIPLIER = 3;
    private static final int STORE_CANDIDATE_FETCH_LIMIT = FINAL_LIMIT * CANDIDATE_FETCH_MULTIPLIER;
    private static final int MAP_STORES_PER_CELL = 12;
    private static final int DEFAULT_MAP_IN_VIEW_PREVIEW_LIMIT = 300;
    private static final int MAX_MAP_IN_VIEW_PREVIEW_LIMIT = 2000;
    private static final int MIN_SUPPORTED_MAP_LEVEL = 1;
    private static final int MAX_SUPPORTED_MAP_LEVEL = 14;
    private static final double TOWN_CLUSTER_MIN_VIEWPORT_SPAN_DEGREES = 0.25;
    private static final double CITY_CLUSTER_MIN_VIEWPORT_SPAN_DEGREES = 2.0;
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<MapStoreClusterResponse> findStoreClustersInView(double minLat, double minLng, double maxLat,
                                                                 double maxLng, String category, int mapLevel) {
        double normalizedMinLat = Math.min(minLat, maxLat);
        double normalizedMaxLat = Math.max(minLat, maxLat);
        double normalizedMinLng = Math.min(minLng, maxLng);
        double normalizedMaxLng = Math.max(minLng, maxLng);
        String normalizedCategory = normalizeCategory(category);
        int normalizedMapLevel = normalizeMapLevel(mapLevel);
        int aggregationMapLevel = resolveClusterAggregationMapLevel(
                normalizedMapLevel,
                normalizedMinLat,
                normalizedMaxLat,
                normalizedMinLng,
                normalizedMaxLng
        );
        ClusterAdministrativeUnit administrativeUnit = resolveClusterAdministrativeUnit(aggregationMapLevel);

        if (mapClusterSnapshotCache.isEnabled()) {
            return mapClusterSnapshotCache.find(administrativeUnit.name(), normalizedMinLat, normalizedMaxLat,
                            normalizedMinLng, normalizedMaxLng, normalizedCategory, normalizedMapLevel)
                    .orElseGet(() -> storeClusterQueryService.findStoreClustersInView(normalizedMinLat,
                            normalizedMaxLat, normalizedMinLng, normalizedMaxLng, normalizedCategory,
                            normalizedMapLevel, administrativeUnit.name()))
                    .stream().map(projection -> toMapStoreClusterResponse(projection, normalizedMapLevel))
                    .collect(Collectors.toCollection(ArrayList::new));
        }

        String cacheKey = createClusterCacheKey(
                normalizedMinLat,
                normalizedMinLng,
                normalizedMaxLat,
                normalizedMaxLng,
                normalizedCategory,
                normalizedMapLevel
        );
        return storeClusterCacheService.getOrLoad(cacheKey, () ->
                storeClusterQueryService.findStoreClustersInView(
                                normalizedMinLat,
                                normalizedMaxLat,
                                normalizedMinLng,
                                normalizedMaxLng,
                                normalizedCategory,
                                normalizedMapLevel,
                                administrativeUnit.name()
                        ).stream()
                        .map(projection -> toMapStoreClusterResponse(projection, normalizedMapLevel))
                        .collect(Collectors.toCollection(ArrayList::new))
        );
    }

    private String createClusterCacheKey(
            double minLat,
            double minLng,
            double maxLat,
            double maxLng,
            String category,
            int mapLevel
    ) {
        return minLat + ":" + minLng + ":" + maxLat + ":" + maxLng + ":"
                + (category == null ? "ALL" : category) + ":" + mapLevel;
    }

    private int normalizeMapLevel(int mapLevel) {
        return Math.max(MIN_SUPPORTED_MAP_LEVEL, Math.min(MAX_SUPPORTED_MAP_LEVEL, mapLevel));
    }

    private int resolveClusterAggregationMapLevel(
            int requestedMapLevel,
            double minLat,
            double maxLat,
            double minLng,
            double maxLng
    ) {
        double viewportSpan = Math.max(maxLat - minLat, maxLng - minLng);
        if (viewportSpan >= CITY_CLUSTER_MIN_VIEWPORT_SPAN_DEGREES) {
            return Math.max(requestedMapLevel, 10);
        }
        if (viewportSpan >= TOWN_CLUSTER_MIN_VIEWPORT_SPAN_DEGREES) {
            return Math.max(requestedMapLevel, 6);
        }
        return requestedMapLevel;
    }

    private ClusterAdministrativeUnit resolveClusterAdministrativeUnit(int mapLevel) {
        if (mapLevel >= 10) {
            return ClusterAdministrativeUnit.CITY;
        }
        if (mapLevel >= 6) {
            return ClusterAdministrativeUnit.TOWN;
        }
        return ClusterAdministrativeUnit.LEGAL_DONG;
    }

    private int resolveClusterTargetMapLevel(String administrativeUnitType, int currentMapLevel) {
        if (administrativeUnitType == null) {
            return Math.max(MIN_SUPPORTED_MAP_LEVEL, currentMapLevel - 1);
        }

        int hierarchyTarget = switch (administrativeUnitType) {
            case "CITY" -> 9;
            case "TOWN" -> 5;
            case "LEGAL_DONG" -> 4;
            default -> currentMapLevel - 1;
        };
        return Math.max(MIN_SUPPORTED_MAP_LEVEL, Math.min(currentMapLevel - 1, hierarchyTarget));
    }

    private MapStoreClusterResponse toMapStoreClusterResponse(
            StoreClusterProjection projection,
            int currentMapLevel
    ) {
        return MapStoreClusterResponse.builder()
                .clusterId(projection.getClusterId())
                .category(projection.getCategory())
                .administrativeUnitType(projection.getAdministrativeUnitType())
                .administrativeUnitName(projection.getAdministrativeUnitName())
                .targetMapLevel(resolveClusterTargetMapLevel(
                        projection.getAdministrativeUnitType(), currentMapLevel))
                .latitude(projection.getLatitude())
                .longitude(projection.getLongitude())
                .count(projection.getCount())
                .build();
    }

    private enum ClusterAdministrativeUnit {
        LEGAL_DONG,
        TOWN,
        CITY
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<MapStorePreviewResponse> findStoresInViewPreviews(double minLat, double minLng, double maxLat,
                                                                  double maxLng, String category, double userLat,
                                                                  double userLng, int limit, boolean includeBenefits) {
        double normalizedMinLat = Math.min(minLat, maxLat);
        double normalizedMaxLat = Math.max(minLat, maxLat);
        double normalizedMinLng = Math.min(minLng, maxLng);
        double normalizedMaxLng = Math.max(minLng, maxLng);
        double centerLat = (normalizedMinLat + normalizedMaxLat) / 2;
        double centerLng = (normalizedMinLng + normalizedMaxLng) / 2;
        String normalizedCategory = normalizeCategory(category);

        List<StorePreviewProjection> previews = storePreviewQueryService.findStorePreviewsInView(
                normalizedMinLat,
                normalizedMaxLat,
                normalizedMinLng,
                normalizedMaxLng,
                centerLat,
                centerLng,
                normalizedCategory,
                normalizeMapInViewPreviewLimit(limit)
        );
        if (previews.isEmpty()) {
            return Collections.emptyList();
        }

        return toMapStorePreviewResponsesFromProjection(previews, userLat, userLng, includeBenefits).stream()
                .sorted(Comparator.comparing(MapStorePreviewResponse::getDistance))
                .toList();
    }

    private int normalizeMapInViewPreviewLimit(int requestedLimit) {
        int effectiveLimit = requestedLimit > 0 ? requestedLimit : DEFAULT_MAP_IN_VIEW_PREVIEW_LIMIT;
        return Math.min(effectiveLimit, MAX_MAP_IN_VIEW_PREVIEW_LIMIT);
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public MapStorePreviewBatchResponse findStoresInViewPreviewBatch(double minLat, double minLng, double maxLat,
                                                                     double maxLng, String category, int limit) {
        double normalizedMinLat = Math.min(minLat, maxLat);
        double normalizedMaxLat = Math.max(minLat, maxLat);
        double normalizedMinLng = Math.min(minLng, maxLng);
        double normalizedMaxLng = Math.max(minLng, maxLng);
        double centerLat = (normalizedMinLat + normalizedMaxLat) / 2;
        double centerLng = (normalizedMinLng + normalizedMaxLng) / 2;

        List<StorePreviewProjection> matchedPreviews = filterPreviewsMatchedToPartner(storePreviewQueryService.findStorePreviewsInView(
                        normalizedMinLat,
                        normalizedMaxLat,
                        normalizedMinLng,
                        normalizedMaxLng,
                        centerLat,
                        centerLng,
                        normalizeCategory(category),
                        normalizeMapInViewPreviewLimit(limit)
                ));
        if (matchedPreviews.isEmpty()) {
            return MapStorePreviewBatchResponse.builder()
                    .stores(List.of())
                    .partners(List.of())
                    .build();
        }

        Map<Long, List<BenefitCacheDto>> benefitsByPartner = partnerBenefitCacheService.getBenefitsBatch(
                matchedPreviews.stream()
                        .map(StorePreviewProjection::getPartnerId)
                        .filter(Objects::nonNull)
                        .distinct()
                        .toList()
        );

        Map<Long, MapStorePreviewBatchResponse.PartnerPreview> partnersById = new LinkedHashMap<>();
        for (StorePreviewProjection preview : matchedPreviews) {
            partnersById.computeIfAbsent(preview.getPartnerId(), partnerId ->
                    MapStorePreviewBatchResponse.PartnerPreview.builder()
                            .partnerId(partnerId)
                            .partnerName(preview.getPartnerName())
                            .category(preview.getCategory() != null ? preview.getCategory().trim() : null)
                            .image(preview.getImage())
                            .tierBenefit(toDistinctTierBenefits(selectBenefits(
                                    benefitsByPartner.getOrDefault(partnerId, List.of()),
                                    preview.getStoreName()
                            )))
                            .build()
            );
        }

        List<MapStorePreviewBatchResponse.StorePreview> stores = matchedPreviews.stream()
                .map(preview -> MapStorePreviewBatchResponse.StorePreview.builder()
                        .storeId(preview.getStoreId())
                        .partnerId(preview.getPartnerId())
                        .storeName(preview.getStoreName())
                        .latitude(preview.getLatitude())
                        .longitude(preview.getLongitude())
                        .address(preview.getAddress())
                        .roadAddress(preview.getRoadAddress())
                        .postCode(preview.getPostCode())
                        .hasCoupon(Boolean.TRUE.equals(preview.getHasCoupon()))
                        .build())
                .toList();

        return MapStorePreviewBatchResponse.builder()
                .stores(stores)
                .partners(new ArrayList<>(partnersById.values()))
                .build();
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<MapStorePreviewResponse> findNearbyPreviews(double lat, double lng, double radiusMeters, double userLat,
                                                            double userLng) {
        List<Long> allStoreIds = storeRepository.findStoreIdsInRadius(
                lat, lng, radiusMeters, STORE_CANDIDATE_FETCH_LIMIT);

        if (allStoreIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> sampledStoreIds = sampleStoreIds(allStoreIds);
        List<Store> stores = storeRepository.findAllByStoreIdInWithPartner(sampledStoreIds);

        return toMapStorePreviewResponses(stores, userLat, userLng).stream()
                .sorted(Comparator.comparing(MapStorePreviewResponse::getDistance))
                .toList();
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<StoreDetailResponse> findNearby(double lat, double lng, double radiusMeters, double userLat,
                                           double userLng) {
        List<Long> allStoreIds = storeRepository.findStoreIdsInRadius(
                lat, lng, radiusMeters, STORE_CANDIDATE_FETCH_LIMIT);

        if (allStoreIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> sampledStoreIds = sampleStoreIds(allStoreIds);
        List<Store> limitedStores = filterStoresMatchedToPartner(storeRepository.findAllByStoreIdInWithPartner(sampledStoreIds));

        List<Long> partnerIds = limitedStores.stream()
                .map(store -> store.getPartner().getPartnerId())
                .distinct()
                .toList();

        // [변경] 기존: benefitRepository.findAllByPartner_PartnerIdIn + tierBenefitRepository.findAllByBenefitIn 으로
        //        allBenefits → partnerToBenefitsMap, allTierBenefits → benefitToTiersMap 두 개의 맵을 구성.
        // 변경 후: partnerBenefitCacheService.getBenefits(partnerId) 호출 한 번으로 혜택+등급혜택 정보를
        //         BenefitCacheDto에 묶어 가져옴. Redis에 캐싱되어 있으면 DB 조회 없이 반환.
        Map<Long, List<BenefitCacheDto>> partnerToBenefitsMap = partnerBenefitCacheService.getBenefitsBatch(partnerIds);

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
                    List<TierBenefitDto> tierBenefitDtos = toDistinctTierBenefits(finalBenefits);
                    double distance = calculateDistance(userLat, userLng,
                            store.getLocation().getY(), store.getLocation().getX());
                    return StoreDetailResponse.of(store, partner, tierBenefitDtos, distance);
                })
                .sorted(Comparator.comparing(StoreDetailResponse::getDistance))
                .toList();
    }

    private double[] computeBoundingBox(double lat, double lng, double radiusMeters) {
        double earthRadius = 6378137.0;
        double dLat = radiusMeters / earthRadius;
        double dLng = radiusMeters / (earthRadius * Math.cos(Math.toRadians(lat)));
        // [minLat, maxLat, minLng, maxLng]
        return new double[]{
            lat - Math.toDegrees(dLat),
            lat + Math.toDegrees(dLat),
            lng - Math.toDegrees(dLng),
            lng + Math.toDegrees(dLng)
        };
    }

    private String normalizeCategory(String category) {
        return category == null || category.isBlank() || category.equalsIgnoreCase("전체")
                ? null
                : category.trim();
    }

    private List<Long> findDistributedStoreIdsForMap(double lat, double lng, double radiusMeters, String category) {
        double[] bbox = computeBoundingBox(lat, lng, radiusMeters);
        double minLat = bbox[0], maxLat = bbox[1], minLng = bbox[2], maxLng = bbox[3];
        double cellLatDegrees = (maxLat - minLat) / DISTRIBUTED_GRID_SIZE;
        double cellLngDegrees = (maxLng - minLng) / DISTRIBUTED_GRID_SIZE;

        return storeRepository.findDistributedStoreIdsWithinRadius(
                category,
                lat,
                lng,
                radiusMeters,
                minLat,
                maxLat,
                minLng,
                maxLng,
                cellLatDegrees,
                cellLngDegrees,
                MAP_STORES_PER_CELL,
                FINAL_LIMIT
        );
    }

    /**
     * DB-level random sorting is intentionally avoided because it is expensive on large geospatial result sets.
     * Fetch a bounded candidate window from the DB, then randomize only that lightweight ID list in the app server.
     */
    private List<Long> sampleStoreIds(List<Long> storeIds) {
        if (storeIds == null || storeIds.isEmpty()) {
            return List.of();
        }
        List<Long> sampled = new ArrayList<>(storeIds);
        Collections.shuffle(sampled);
        return sampled.subList(0, Math.min(sampled.size(), FINAL_LIMIT));
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<StoreDetailResponse> findNearbyDistributedForMap(double lat, double lng, double radiusMeters,
                                                                 String category, double userLat, double userLng) {
        String normalizedCategory = normalizeCategory(category);

        List<Long> distributedStoreIds = findDistributedStoreIdsForMap(lat, lng, radiusMeters, normalizedCategory);
        if (distributedStoreIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<Store> stores = storeRepository.findAllByStoreIdInWithPartner(distributedStoreIds);
        return toStoreDetailResponses(stores, userLat, userLng).stream()
                .sorted(Comparator.comparing(StoreDetailResponse::getDistance)
                        .thenComparing(response -> response.getStore().getStoreId()))
                .toList();
    }


    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<MapStorePreviewResponse> findNearbyByCategoryPreviews(double lat, double lng, double radiusMeters,
                                                                      String category, double userLat, double userLng) {
        if (category == null || category.isBlank() || category.equalsIgnoreCase("전체")) {
            return findNearbyPreviews(lat, lng, radiusMeters, userLat, userLng);
        }

        String normalizedCategory = category.trim();
        List<Long> storeIds = storeRepository.findStoreIdsByCategoryWithinRadius(
                normalizedCategory, lat, lng, radiusMeters, STORE_CANDIDATE_FETCH_LIMIT);
        if (storeIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> sampledStoreIds = sampleStoreIds(storeIds);
        List<Store> stores = storeRepository.findAllByStoreIdInWithPartner(sampledStoreIds);

        return toMapStorePreviewResponses(stores, userLat, userLng).stream()
                .sorted(Comparator.comparing(MapStorePreviewResponse::getDistance))
                .toList();
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<StoreDetailResponse> findNearbyByCategory(double lat, double lng, double radiusMeters, String category,
                                                     double userLat, double userLng) {
        if (category == null || category.isBlank() || category.equalsIgnoreCase("전체")) {
            return findNearby(lat, lng, radiusMeters, userLat, userLng);
        }

        log.debug("카테고리 기반 반경 검색 실행: {}, 반경: {}m", category, radiusMeters);

        List<Long> storeIds = storeRepository.findStoreIdsByCategoryWithinRadius(
                category, lat, lng, radiusMeters, STORE_CANDIDATE_FETCH_LIMIT);
        if (storeIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> sampledStoreIds = sampleStoreIds(storeIds);
        List<Store> limitedStores = filterStoresMatchedToPartner(storeRepository.findAllByStoreIdInWithPartner(sampledStoreIds));
        if (limitedStores.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> partnerIds = limitedStores.stream()
                .map(store -> store.getPartner().getPartnerId())
                .distinct()
                .toList();

        Map<Long, List<BenefitCacheDto>> partnerToBenefitsMap = partnerBenefitCacheService.getBenefitsBatch(partnerIds);

        return limitedStores.stream()
                .map(store -> {
                    Partner partner = store.getPartner();
                    List<BenefitCacheDto> finalBenefits = selectBenefits(
                            partnerToBenefitsMap.getOrDefault(partner.getPartnerId(), List.of()),
                            store.getStoreName()
                    );
                    // [변경] 기존: benefitToTiersMap에서 조회 후 TierBenefitDto로 변환.
                    // 변경 후: BenefitCacheDto.getTierBenefits()로 이미 변환된 DTO 리스트를 바로 사용.
                    List<TierBenefitDto> tierBenefitDtos = toDistinctTierBenefits(finalBenefits);
                    double distance = calculateDistance(userLat, userLng,
                            store.getLocation().getY(), store.getLocation().getX());
                    return StoreDetailResponse.of(store, partner, tierBenefitDtos, distance);
                })
                .sorted(Comparator.comparing(StoreDetailResponse::getDistance))
                .toList();
    }


    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<MapStorePreviewResponse> findNearbyByKeywordPreviews(double lat, double lng, String category,
                                                                     String keyword, double userLat, double userLng) {
        return toMapStorePreviewResponses(findKeywordStores(lat, lng, category, keyword, userLat, userLng),
                userLat, userLng);
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<StoreDetailResponse> findNearbyByKeyword(double lat, double lng, String category,
                                                    String keyword, double userLat, double userLng) {
        return toStoreDetailResponses(findKeywordStores(lat, lng, category, keyword, userLat, userLng),
                userLat, userLng);
    }

    private List<Store> findKeywordStores(double lat, double lng, String category,
                                          String keyword, double userLat, double userLng) {
        if (keyword == null || keyword.isBlank()) {
            throw new StoreKeywordException(StoreCode.KEYWORD_REQUEST);
        }

        String normalizedKeyword = keyword.trim();

        if (category != null && (category.isBlank() || category.equalsIgnoreCase("전체"))) {
            category = null;
        } else if (category != null) {
            category = category.trim();
        }

        StoreSearchResult searchResult;
        try {
            // 1단계: ES nori 형태소 분석으로 브랜드 매치 / 매장명 매치 분리
            searchResult = storeSearchService.searchByKeyword(normalizedKeyword, category);
        } catch (RuntimeException e) {
            log.warn("ES 매장 검색 실패, DB 키워드 검색으로 대체: keyword={}, category={}", normalizedKeyword, category, e);
            return findStoresWithPartnerInRequestedOrder(storeRepository.searchNearbyStoreIds(lng, lat, category, normalizedKeyword));
        }
        if (searchResult.isEmpty()) {
            log.info("ES 매장 검색 결과 없음, DB 키워드 검색으로 대체: keyword={}, category={}", normalizedKeyword, category);
            return findStoresWithPartnerInRequestedOrder(storeRepository.searchNearbyStoreIds(lng, lat, category, normalizedKeyword));
        }

        // 전국 ES 후보 제한 밖의 가까운 정확 일치 매장도 지리 검색으로 보충한다.
        List<Long> nearbyIds = storeRepository.searchNearbyStoreIds(lng, lat, category, normalizedKeyword);
        List<Long> allIds = Stream.of(nearbyIds, searchResult.brandMatchIds(), searchResult.nameMatchIds())
                .flatMap(List::stream).distinct().toList();

        List<Store> allStores = filterStoresMatchedToPartner(storeRepository.findAllByStoreIdInWithPartner(allIds));
        if (allStores.isEmpty()) {
            log.info("ES 매장 검색 ID가 DB에서 조회되지 않아 DB 키워드 검색으로 대체: keyword={}, category={}", normalizedKeyword, category);
            return findStoresWithPartnerInRequestedOrder(storeRepository.searchNearbyStoreIds(lng, lat, category, normalizedKeyword));
        }

        Map<Long, Store> storesById = allStores.stream()
                .collect(Collectors.toMap(Store::getStoreId, store -> store));
        Map<Long, Double> distances = new HashMap<>();
        for (Store store : allStores) {
            distances.put(store.getStoreId(), userLat == 0 || userLng == 0 ? 0.0
                    : calculateDistance(userLat, userLng, store.getLocation().getY(), store.getLocation().getX()));
        }
        Comparator<Store> byDistance = Comparator.comparingDouble(store -> distances.get(store.getStoreId()));

        List<Long> strictBrandMatchIds = Stream.concat(nearbyIds.stream(), searchResult.brandMatchIds().stream()).distinct()
                .filter(id -> isStrictPartnerKeywordMatch(storesById.get(id), normalizedKeyword))
                .toList();
        Set<Long> brandIds = new HashSet<>(strictBrandMatchIds);
        List<Long> nameMatchIds = Stream.concat(nearbyIds.stream(), searchResult.nameMatchIds().stream())
                .distinct().filter(id -> !brandIds.contains(id)).toList();
        if (strictBrandMatchIds.isEmpty() && nameMatchIds.isEmpty()) {
            log.info("ES 브랜드 검색 결과가 키워드와 정확히 맞지 않아 DB 키워드 검색으로 대체: keyword={}, category={}",
                    normalizedKeyword, category);
            return findStoresWithPartnerInRequestedOrder(storeRepository.searchNearbyStoreIds(lng, lat, category, normalizedKeyword));
        }

        // 브랜드 매치(partnerName) 그룹은 실제 파트너명이 키워드와 포함 관계일 때만 우선 노출한다.
        // ES/Nori match는 "팬시랜드"와 "서울랜드"처럼 공통 토큰(랜드)만으로도 매칭될 수 있으므로,
        // 검증되지 않은 브랜드 매치를 먼저 보여주면 무관한 혜택이 적용된 것처럼 보인다.
        return Stream.concat(
                strictBrandMatchIds.stream()
                        .map(storesById::get)
                        .filter(Objects::nonNull)
                        .sorted(byDistance),
                nameMatchIds.stream()
                        .map(storesById::get)
                        .filter(Objects::nonNull)
                        .sorted(byDistance)
        ).toList();
    }


    private List<Store> filterStoresMatchedToPartner(List<Store> stores) {
        if (stores == null || stores.isEmpty()) return List.of();
        Map<String, PartnerNames> names = new HashMap<>();
        return stores.stream().filter(store -> store != null && store.getPartner() != null
                && isStoreMatchedToPartner(store.getStoreName(), store.getBusiness(),
                        store.getPartner().getPartnerName(), names)).toList();
    }

    private List<StorePreviewProjection> filterPreviewsMatchedToPartner(List<StorePreviewProjection> previews) {
        Map<String, PartnerNames> names = new HashMap<>();
        return previews.stream().filter(preview -> preview != null
                && isStoreMatchedToPartner(preview.getStoreName(), preview.getBusiness(),
                        preview.getPartnerName(), names)).toList();
    }

    private record PartnerNames(String normalized, List<String> aliases) {}

    private boolean isStoreMatchedToPartner(String storeName, String business, String partnerName,
                                             Map<String, PartnerNames> namesByPartner) {
        PartnerNames names = namesByPartner.computeIfAbsent(partnerName, name -> {
            String normalized = normalizeSearchText(name);
            List<String> aliases = switch (normalized) {
                case "gs25", "지에스25" -> List.of("gs25", "지에스25");
                case "cu", "씨유" -> List.of("cu", "씨유");
                case "세븐일레븐", "7eleven" -> List.of("세븐일레븐", "7eleven");
                default -> List.of(normalized);
            };
            return new PartnerNames(normalized, aliases);
        });
        String normalizedStoreName = normalizeSearchText(storeName);
        return names.aliases().stream().anyMatch(alias -> !alias.isBlank() && normalizedStoreName.contains(alias))
                && StorePartnerBusinessPolicy.matchesNormalizedPartner(names.normalized(), business);
    }

    private boolean isStrictPartnerKeywordMatch(Store response, String keyword) {
        if (response == null || response.getPartner() == null) {
            return false;
        }
        String normalizedPartnerName = normalizeSearchText(response.getPartner().getPartnerName());
        String normalizedKeyword = normalizeSearchText(keyword);
        return !normalizedPartnerName.isBlank()
                && !normalizedKeyword.isBlank()
                && (normalizedPartnerName.contains(normalizedKeyword)
                || normalizedKeyword.contains(normalizedPartnerName));
    }

    private String normalizeSearchText(String text) {
        return StorePartnerBusinessPolicy.normalize(text);
    }


    private List<Store> findStoresWithPartnerInRequestedOrder(List<Long> storeIds) {
        if (storeIds == null || storeIds.isEmpty()) {
            return Collections.emptyList();
        }

        Map<Long, Store> storesById = storeRepository.findAllByStoreIdInWithPartner(storeIds).stream()
                .collect(Collectors.toMap(
                        Store::getStoreId,
                        store -> store,
                        (first, ignored) -> first,
                        LinkedHashMap::new
                ));

        return storeIds.stream()
                .map(storesById::get)
                .filter(Objects::nonNull)
                .toList();
    }


    private List<MapStorePreviewResponse> toMapStorePreviewResponsesFromProjection(List<StorePreviewProjection> previews,
                                                                                   double userLat, double userLng,
                                                                                   boolean includeBenefits) {
        List<StorePreviewProjection> matchedPreviews = filterPreviewsMatchedToPartner(previews);
        if (matchedPreviews.isEmpty()) {
            return Collections.emptyList();
        }

        Map<Long, List<BenefitCacheDto>> partnerToBenefitsMap = includeBenefits
                ? partnerBenefitCacheService.getBenefitsBatch(matchedPreviews.stream()
                        .map(StorePreviewProjection::getPartnerId)
                        .filter(Objects::nonNull)
                        .distinct()
                        .toList())
                : Collections.emptyMap();

        return matchedPreviews.stream()
                .map(preview -> {
                    double distance = userLat == 0 || userLng == 0
                            ? 0 : calculateDistance(userLat, userLng, preview.getLatitude(), preview.getLongitude());
                    List<TierBenefitDto> tierBenefitDtos = includeBenefits
                            ? toDistinctTierBenefits(selectBenefits(
                                    partnerToBenefitsMap.getOrDefault(preview.getPartnerId(), List.of()),
                                    preview.getStoreName()
                            ))
                            : List.of();
                    return toMapStorePreviewResponse(preview, tierBenefitDtos, distance);
                })
                .toList();
    }

    private MapStorePreviewResponse toMapStorePreviewResponse(StorePreviewProjection preview,
                                                              List<TierBenefitDto> tierBenefitDtos,
                                                              double distance) {
        return MapStorePreviewResponse.builder()
                .storeId(preview.getStoreId())
                .partnerId(preview.getPartnerId())
                .storeName(preview.getStoreName())
                .partnerName(preview.getPartnerName())
                .category(preview.getCategory() != null ? preview.getCategory().trim() : null)
                .image(preview.getImage())
                .latitude(preview.getLatitude())
                .longitude(preview.getLongitude())
                .address(preview.getAddress())
                .roadName(preview.getRoadName())
                .roadAddress(preview.getRoadAddress())
                .postCode(preview.getPostCode())
                .hasCoupon(Boolean.TRUE.equals(preview.getHasCoupon()))
                .tierBenefit(tierBenefitDtos)
                .distance(distance)
                .build();
    }

    private List<MapStorePreviewResponse> toMapStorePreviewResponses(List<Store> stores, double userLat, double userLng) {
        List<Store> matchedStores = filterStoresMatchedToPartner(stores);
        if (matchedStores.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> partnerIds = matchedStores.stream()
                .map(store -> store.getPartner().getPartnerId())
                .distinct()
                .toList();

        Map<Long, List<BenefitCacheDto>> partnerToBenefitsMap = partnerBenefitCacheService.getBenefitsBatch(partnerIds);

        return matchedStores.stream()
                .map(store -> {
                    Partner partner = store.getPartner();
                    double distance = userLat == 0 || userLng == 0
                            ? 0 : calculateDistance(userLat, userLng,
                            store.getLocation().getY(), store.getLocation().getX());
                    List<BenefitCacheDto> finalBenefits = selectBenefits(
                            partnerToBenefitsMap.getOrDefault(partner.getPartnerId(), List.of()),
                            store.getStoreName()
                    );
                    List<TierBenefitDto> tierBenefitDtos = toDistinctTierBenefits(finalBenefits);
                    return MapStorePreviewResponse.of(store, partner, tierBenefitDtos, distance);
                })
                .toList();
    }

    private List<StoreDetailResponse> toStoreDetailResponses(List<Store> stores, double userLat, double userLng) {
        return toStoreDetailResponses(stores, userLat, userLng, null, false);
    }

    private List<StoreDetailResponse> toStoreDetailResponses(List<Store> stores, double userLat, double userLng,
                                                            Carrier carrier, boolean includeZeroCoordinates) {
        List<Store> matchedStores = filterStoresMatchedToPartner(stores);
        if (matchedStores.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> partnerIds = matchedStores.stream()
                .map(store -> store.getPartner().getPartnerId())
                .distinct()
                .toList();

        Map<Long, List<BenefitCacheDto>> partnerToBenefitsMap = partnerBenefitCacheService.getBenefitsBatch(partnerIds);

        return matchedStores.stream()
                .map(store -> {
                    Partner partner = store.getPartner();
                    double distance = !includeZeroCoordinates && (userLat == 0 || userLng == 0)
                            ? 0 : calculateDistance(userLat, userLng,
                            store.getLocation().getY(), store.getLocation().getX());
                    List<BenefitCacheDto> finalBenefits = selectBenefits(
                            partnerToBenefitsMap.getOrDefault(partner.getPartnerId(), List.of()),
                            store.getStoreName()
                    );
                    List<TierBenefitDto> tierBenefitDtos = toDistinctTierBenefits(finalBenefits);
                    if (carrier != null) {
                        tierBenefitDtos = tierBenefitDtos.stream()
                                .filter(tier -> tier.getCarrier() == null || tier.getCarrier() == carrier).toList();
                        if (tierBenefitDtos.isEmpty()) return null;
                    }
                    return StoreDetailResponse.of(store, partner, tierBenefitDtos, distance);
                })
                .filter(Objects::nonNull)
                .toList();
    }

    @Override
    public List<StoreDetailResponse> findNearbyForMobile(double lat, double lng, double userLat, double userLng,
                                                        double radiusMeters, String carrier, String category,
                                                        String keyword, String partnerName) {
        List<Store> candidates;
        boolean distributed = false;
        if (partnerName != null && !partnerName.isBlank()) {
            Partner partner = partnerRepository.findByPartnerName(partnerName.trim())
                    .orElseThrow(() -> new PartnerNotFoundException(PartnerCode.PARTNER_NOT_FOUND));
            candidates = findStoresWithPartnerInRequestedOrder(
                    storeRepository.searchNearbyStoreIdsByPartnerId(lng, lat, partner.getPartnerId()));
        } else if (keyword != null && !keyword.isBlank()) {
            candidates = findKeywordStores(lat, lng, normalizeCategory(category), keyword.trim(), userLat, userLng);
        } else {
            distributed = true;
            List<Long> ids = findDistributedStoreIdsForMap(lat, lng, radiusMeters, normalizeCategory(category));
            candidates = ids.isEmpty() ? List.of() : storeRepository.findAllByStoreIdInWithPartner(ids);
        }
        List<Store> inRadius = candidates.stream()
                .filter(store -> withinMobileRadius(store, lat, lng, radiusMeters)).toList();
        Carrier carrierFilter = null;
        if (carrier != null && !carrier.isBlank()) {
            try { carrierFilter = Carrier.valueOf(carrier.trim().toUpperCase(java.util.Locale.ROOT)); }
            catch (IllegalArgumentException ignored) { /* Legacy mobile accepts unknown carrier as all. */ }
        }
        List<StoreDetailResponse> responses = toStoreDetailResponses(inRadius, userLat, userLng, carrierFilter,
                partnerName != null && !partnerName.isBlank());
        return distributed ? responses.stream().sorted(Comparator.comparing(StoreDetailResponse::getDistance)
                .thenComparing(response -> response.getStore().getStoreId())).toList() : responses;
    }

    private boolean withinMobileRadius(Store store, double lat, double lng, double radiusMeters) {
        double latitude = store.getLocation().getY();
        double longitude = store.getLocation().getX();
        double dLat = Math.toRadians(latitude - lat), dLng = Math.toRadians(longitude - lng);
        double haversine = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat)) * Math.cos(Math.toRadians(latitude))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        double normalized = Math.max(0, Math.min(1, haversine));
        return 6_378_137.0 * 2 * Math.atan2(Math.sqrt(normalized), Math.sqrt(1 - normalized)) <= radiusMeters;
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<StoreDetailResponse> findNearbyByPartnerName(double lat, double lng, String partnerName, double userLat,
                                                        double userLng) {
        if (partnerName == null || partnerName.isBlank()) {
            throw new StoreKeywordException(StoreCode.PARTNERNAME_REQUEST);
        }

        Partner partner = partnerRepository.findByPartnerName(partnerName)
                .orElseThrow(() -> new PartnerNotFoundException(PartnerCode.PARTNER_NOT_FOUND));

        return findNearbyByPartner(lat, lng, partner, null, userLat, userLng);
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<StoreDetailResponse> findNearbyByBenefitCandidate(double lat,
                                                                  double lng,
                                                                  Long partnerId,
                                                                  String partnerName,
                                                                  String category,
                                                                  Long benefitId,
                                                                  double userLat,
                                                                  double userLng) {
        List<Partner> partners = resolveCandidatePartners(partnerId, partnerName, category);
        if (partners.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> partnerIds = partners.stream()
                .map(Partner::getPartnerId)
                .distinct()
                .toList();
        Map<Long, Integer> partnerOrder = new HashMap<>();
        for (int index = 0; index < partnerIds.size(); index++) {
            partnerOrder.put(partnerIds.get(index), index);
        }
        List<Long> storeIds = storeRepository.searchNearbyStoreIdsByPartnerIds(lng, lat, partnerIds);
        List<Store> stores = filterStoresMatchedToPartner(findStoresWithPartnerInRequestedOrder(storeIds));
        if (stores.isEmpty()) {
            return Collections.emptyList();
        }

        Map<Long, List<BenefitCacheDto>> benefitsByPartner = partnerBenefitCacheService.getBenefitsBatch(partnerIds);
        return stores.stream()
                .map(store -> {
                    Partner partner = store.getPartner();
                    List<BenefitCacheDto> partnerBenefits = filterBenefitsById(
                            benefitsByPartner.getOrDefault(partner.getPartnerId(), List.of()),
                            benefitId
                    );
                    if (benefitId != null && partnerBenefits.isEmpty()) {
                        return null;
                    }
                    double distance = calculateDistance(
                            userLat,
                            userLng,
                            store.getLocation().getY(),
                            store.getLocation().getX()
                    );
                    List<BenefitCacheDto> finalBenefits = selectBenefits(partnerBenefits, store.getStoreName());
                    return StoreDetailResponse.of(
                            store,
                            partner,
                            toDistinctTierBenefits(finalBenefits),
                            distance
                    );
                })
                .filter(Objects::nonNull)
                .sorted(Comparator
                        .comparingInt((StoreDetailResponse response) -> partnerOrder.getOrDefault(
                                response.getPartner().getPartnerId(),
                                Integer.MAX_VALUE
                        ))
                        .thenComparing(StoreDetailResponse::getDistance))
                .toList();
    }

    private List<Partner> resolveCandidatePartners(Long partnerId, String partnerName, String category) {
        if (partnerId != null) {
            return partnerRepository.findByPartnerId(partnerId)
                    .filter(partner -> isCategoryMatched(partner.getCategory(), category))
                    .map(List::of)
                    .orElseGet(Collections::emptyList);
        }

        if (partnerName == null || partnerName.isBlank()) {
            throw new StoreKeywordException(StoreCode.PARTNERNAME_REQUEST);
        }

        List<Partner> partners = partnerRepository.findAllByPartnerName(partnerName);
        if (partners == null || partners.isEmpty()) {
            throw new PartnerNotFoundException(PartnerCode.PARTNER_NOT_FOUND);
        }

        return partners.stream()
                .filter(partner -> isCategoryMatched(partner.getCategory(), category))
                .toList();
    }

    private List<StoreDetailResponse> findNearbyByPartner(double lat,
                                                          double lng,
                                                          Partner partner,
                                                          Long benefitId,
                                                          double userLat,
                                                          double userLng) {
        List<Long> storeIds = storeRepository.searchNearbyStoreIdsByPartnerId(lng, lat, partner.getPartnerId());
        List<Store> stores = findStoresWithPartnerInRequestedOrder(storeIds);

        // [변경] 기존: 루프 내부에서 매 매장(store)마다 benefitRepository.findAllByPartner_PartnerId()와
        //        tierBenefitRepository.findAllByBenefit_BenefitId()를 반복 호출 → 매장 수만큼 DB 쿼리 발생(N+1).
        // 변경 후: 루프 밖에서 캐시 서비스를 통해 해당 파트너의 혜택을 한 번만 조회.
        //         이후 루프에서는 DB 호출 없이 캐시 결과를 재사용.
        List<BenefitCacheDto> partnerBenefits = filterBenefitsById(
                partnerBenefitCacheService.getBenefits(partner.getPartnerId()),
                benefitId
        );
        if (benefitId != null && partnerBenefits.isEmpty()) {
            return Collections.emptyList();
        }

        return filterStoresMatchedToPartner(stores).stream()
                .map(store -> {
                    double distance = calculateDistance(userLat, userLng,
                            store.getLocation().getY(), store.getLocation().getX());
                    List<BenefitCacheDto> finalBenefits = selectBenefits(partnerBenefits, store.getStoreName());
                    // [변경] 기존: tierBenefitRepository를 루프 내에서 직접 호출하여 TierBenefitDto 생성.
                    // 변경 후: BenefitCacheDto.getTierBenefits()에서 바로 꺼냄.
                    List<TierBenefitDto> tierBenefitDtos = toDistinctTierBenefits(finalBenefits);
                    return StoreDetailResponse.of(store, partner, tierBenefitDtos, distance);
                })
                .toList();
    }

    private List<BenefitCacheDto> filterBenefitsById(List<BenefitCacheDto> benefits, Long benefitId) {
        if (benefitId == null) {
            return benefits == null ? Collections.emptyList() : benefits;
        }
        if (benefits == null || benefits.isEmpty()) {
            return Collections.emptyList();
        }
        return benefits.stream()
                .filter(benefit -> benefitId.equals(benefit.getBenefitId()))
                .toList();
    }

    private boolean isCategoryMatched(String partnerCategory, String expectedCategory) {
        String normalizedExpected = normalizeSearchText(expectedCategory);
        if (normalizedExpected.isBlank()) {
            return true;
        }

        String normalizedPartnerCategory = normalizeSearchText(partnerCategory);
        return !normalizedPartnerCategory.isBlank()
                && (normalizedPartnerCategory.contains(normalizedExpected)
                || normalizedExpected.contains(normalizedPartnerCategory));
    }


    private List<TierBenefitDto> toDistinctTierBenefits(List<BenefitCacheDto> benefits) {
        Map<String, TierBenefitDto> distinct = benefits.stream()
                .flatMap(benefit -> benefit.getTierBenefits().stream()
                        .map(tier -> withBenefitId(tier, benefit.getBenefitId())))
                .collect(Collectors.toMap(
                        tier -> String.join("|",
                                String.valueOf(tier.getCarrier()),
                                String.valueOf(tier.getGrade()),
                                String.valueOf(tier.getContext())
                        ),
                        tier -> tier,
                        (first, ignored) -> first,
                        LinkedHashMap::new
                ));

        return new ArrayList<>(distinct.values());
    }

    private TierBenefitDto withBenefitId(TierBenefitDto tier, Long benefitId) {
        if (tier.getBenefitId() != null || benefitId == null) {
            return tier;
        }

        return TierBenefitDto.builder()
                .benefitId(benefitId)
                .carrier(tier.getCarrier())
                .grade(tier.getGrade())
                .context(tier.getContext())
                .onlineContext(tier.getOnlineContext())
                .offlineContext(tier.getOfflineContext())
                .build();
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

        List<BenefitCacheDto> offlineBenefits = benefits.stream()
                .filter(BenefitCacheDto::isOfflineAvailable)
                .toList();
        if (!offlineBenefits.isEmpty()) {
            return offlineBenefits;
        }

        List<BenefitCacheDto> legacyOfflineBenefits = benefits.stream()
                .filter(b -> b.getBenefitName() != null && b.getBenefitName().contains("오프라인"))
                .toList();
        if (!legacyOfflineBenefits.isEmpty()) {
            return legacyOfflineBenefits;
        }

        if (benefits.size() >= 3) {
            List<BenefitCacheDto> matched = benefits.stream()
                    .filter(b -> b.getBenefitName().equals(storeName))
                    .toList();
            if (!matched.isEmpty()) {
                return matched;
            }
        }
        return benefits;
    }
}
