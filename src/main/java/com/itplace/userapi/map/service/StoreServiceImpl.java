package com.itplace.userapi.map.service;

import com.itplace.userapi.map.StoreCode;
import com.itplace.userapi.map.dto.BenefitCacheDto;
import com.itplace.userapi.map.dto.response.MapStorePreviewResponse;
import com.itplace.userapi.map.dto.response.MapStoreClusterResponse;
import com.itplace.userapi.map.dto.response.StoreDetailResponse;
import com.itplace.userapi.map.dto.response.TierBenefitDto;
import com.itplace.userapi.map.entity.Store;
import com.itplace.userapi.map.exception.StoreKeywordException;
import com.itplace.userapi.map.repository.StoreRepository;
import com.itplace.userapi.map.repository.projection.StorePreviewProjection;
import com.itplace.userapi.map.repository.projection.StoreClusterProjection;
import com.itplace.userapi.partner.PartnerCode;
import com.itplace.userapi.partner.entity.Partner;
import com.itplace.userapi.partner.exception.PartnerNotFoundException;
import com.itplace.userapi.partner.repository.PartnerRepository;
import jakarta.annotation.PreDestroy;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
    private final EntityManager entityManager;

    private static final int GRID_SIZE = 5;
    private static final int STORES_PER_CELL = 15;
    private static final int FINAL_LIMIT = 300;
    private static final int CANDIDATE_FETCH_MULTIPLIER = 3;
    private static final int STORE_CANDIDATE_FETCH_LIMIT = FINAL_LIMIT * CANDIDATE_FETCH_MULTIPLIER;
    private static final int WIDE_RADIUS_THRESHOLD = 10000;
    private static final int MAP_DISTRIBUTION_THRESHOLD = 1000;
    private static final int MAP_STORES_PER_CELL = 12;
    private static final int DEFAULT_MAP_IN_VIEW_PREVIEW_LIMIT = 500;
    private static final int MAX_MAP_IN_VIEW_PREVIEW_LIMIT = 2000;
    private static final ExecutorService GRID_EXECUTOR = Executors.newFixedThreadPool(10);

    @PreDestroy
    public void shutdownExecutor() {
        GRID_EXECUTOR.shutdown();
    }



    @Override
    @Transactional(readOnly = true)
    public List<MapStoreClusterResponse> findStoreClustersInView(double minLat, double minLng, double maxLat,
                                                                 double maxLng, String category, int mapLevel) {
        double normalizedMinLat = Math.min(minLat, maxLat);
        double normalizedMaxLat = Math.max(minLat, maxLat);
        double normalizedMinLng = Math.min(minLng, maxLng);
        double normalizedMaxLng = Math.max(minLng, maxLng);
        String normalizedCategory = normalizeCategory(category);

        disablePostgresJitForCurrentTransaction();

        return storeRepository.findStoreClustersInView(
                        normalizedMinLat,
                        normalizedMaxLat,
                        normalizedMinLng,
                        normalizedMaxLng,
                        normalizedCategory,
                        resolveClusterEpsMeters(mapLevel),
                        resolveMaxClusterStoreCount(mapLevel),
                        resolveMaxClusterSplits(mapLevel),
                        resolveClusterLimit(mapLevel)
                ).stream()
                .map(this::toMapStoreClusterResponse)
                .toList();
    }


    private void disablePostgresJitForCurrentTransaction() {
        try {
            entityManager.createNativeQuery("SET LOCAL jit = off").executeUpdate();
        } catch (RuntimeException exception) {
            log.debug("클러스터 조회 JIT 비활성화 설정을 건너뜁니다.", exception);
        }
    }

    private double resolveClusterEpsMeters(int mapLevel) {
        if (mapLevel >= 9) {
            return 1800;
        }
        if (mapLevel >= 8) {
            return 1200;
        }
        if (mapLevel >= 7) {
            return 750;
        }
        if (mapLevel >= 6) {
            return 420;
        }
        return 240;
    }

    private int resolveMaxClusterStoreCount(int mapLevel) {
        if (mapLevel >= 9) {
            return 800;
        }
        if (mapLevel >= 8) {
            return 500;
        }
        if (mapLevel >= 7) {
            return 300;
        }
        if (mapLevel >= 6) {
            return 120;
        }
        return 70;
    }

    private int resolveMaxClusterSplits(int mapLevel) {
        if (mapLevel >= 9) {
            return 12;
        }
        if (mapLevel >= 8) {
            return 20;
        }
        if (mapLevel >= 7) {
            return 32;
        }
        return 64;
    }

    private int resolveClusterLimit(int mapLevel) {
        if (mapLevel >= 9) {
            return 120;
        }
        if (mapLevel >= 8) {
            return 160;
        }
        if (mapLevel >= 7) {
            return 220;
        }
        if (mapLevel >= 6) {
            return 280;
        }
        return 320;
    }

    private MapStoreClusterResponse toMapStoreClusterResponse(StoreClusterProjection projection) {
        return MapStoreClusterResponse.builder()
                .clusterId(projection.getClusterId())
                .category(projection.getCategory())
                .latitude(projection.getLatitude())
                .longitude(projection.getLongitude())
                .count(projection.getCount())
                .build();
    }

    @Override
    @Transactional(readOnly = true)
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

        List<StorePreviewProjection> previews = storeRepository.findStorePreviewsInView(
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
    @Transactional(readOnly = true)
    public List<MapStorePreviewResponse> findNearbyPreviews(double lat, double lng, double radiusMeters, double userLat,
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

        List<Long> sampledStoreIds = sampleStoreIds(allStoreIds);
        List<Store> stores = storeRepository.findAllByStoreIdInWithPartner(sampledStoreIds);

        return toMapStorePreviewResponses(stores, userLat, userLng).stream()
                .sorted(Comparator.comparing(MapStorePreviewResponse::getDistance))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<StoreDetailResponse> findNearby(double lat, double lng, double radiusMeters, double userLat,
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

    private List<Long> findNearbyWithSingleQuery(double lat, double lng, double radiusMeters) {
        return storeRepository.findStoreIdsInRadius(lat, lng, radiusMeters, STORE_CANDIDATE_FETCH_LIMIT);
    }

    private List<Long> findNearbyWithGridSampling(double lat, double lng, double radiusMeters) {
        double[] bbox = computeBoundingBox(lat, lng, radiusMeters);
        double minLat = bbox[0], maxLat = bbox[1], minLng = bbox[2], maxLng = bbox[3];

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
                        storeRepository.findStoreIdsInBounds(cellMinLat, cellMaxLat, cellMinLng, cellMaxLng, STORES_PER_CELL),
                        GRID_EXECUTOR
                ));
            }
        }

        return awaitGridResults(futures);
    }

    private List<Long> findDistributedStoreIdsForMap(double lat, double lng, double radiusMeters, String category) {
        double[] bbox = computeBoundingBox(lat, lng, radiusMeters);
        double minLat = bbox[0], maxLat = bbox[1], minLng = bbox[2], maxLng = bbox[3];

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
                                storeRepository.findStoreIdsInCellWithinRadius(
                                        category,
                                        lat,
                                        lng,
                                        radiusMeters,
                                        cellMinLat,
                                        cellMaxLat,
                                        cellMinLng,
                                        cellMaxLng,
                                        MAP_STORES_PER_CELL),
                        GRID_EXECUTOR
                ));
            }
        }

        return awaitGridResults(futures);
    }

    private List<Long> awaitGridResults(List<CompletableFuture<List<Long>>> futures) {
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(10, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("그리드 검색 타임아웃 (10s), 완료된 결과만 반환");
        } catch (ExecutionException | InterruptedException e) {
            log.error("그리드 검색 중 오류", e);
            Thread.currentThread().interrupt();
        }

        return futures.stream()
                .filter(f -> f.isDone() && !f.isCompletedExceptionally())
                .map(CompletableFuture::join)
                .flatMap(List::stream)
                .distinct()
                .collect(Collectors.toList());
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
    @Transactional(readOnly = true)
    public List<StoreDetailResponse> findNearbyDistributedForMap(double lat, double lng, double radiusMeters,
                                                                 String category, double userLat, double userLng) {
        String normalizedCategory = normalizeCategory(category);

        if (radiusMeters <= MAP_DISTRIBUTION_THRESHOLD) {
            return normalizedCategory == null
                    ? findNearby(lat, lng, radiusMeters, userLat, userLng)
                    : findNearbyByCategory(lat, lng, radiusMeters, normalizedCategory, userLat, userLng);
        }

        List<Long> distributedStoreIds = findDistributedStoreIdsForMap(lat, lng, radiusMeters, normalizedCategory);
        if (distributedStoreIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> sampledStoreIds = sampleStoreIds(distributedStoreIds);
        List<Store> stores = storeRepository.findAllByStoreIdInWithPartner(sampledStoreIds);
        return toStoreDetailResponses(stores, userLat, userLng).stream()
                .sorted(Comparator.comparing(StoreDetailResponse::getDistance))
                .toList();
    }


    @Override
    @Transactional(readOnly = true)
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
    @Transactional(readOnly = true)
    public List<StoreDetailResponse> findNearbyByCategory(double lat, double lng, double radiusMeters, String category,
                                                     double userLat, double userLng) {
        if (category == null || category.isBlank() || category.equalsIgnoreCase("전체")) {
            return findNearby(lat, lng, radiusMeters, userLat, userLng);
        }

        log.info("카테고리 기반 반경 검색 실행: {}, 반경: {}m", category, radiusMeters);

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
    @Transactional(readOnly = true)
    public List<MapStorePreviewResponse> findNearbyByKeywordPreviews(double lat, double lng, String category,
                                                                     String keyword, double userLat, double userLng) {
        return findNearbyByKeyword(lat, lng, category, keyword, userLat, userLng).stream()
                .map(MapStorePreviewResponse::fromDetail)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<StoreDetailResponse> findNearbyByKeyword(double lat, double lng, String category,
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
            return searchNearbyStoresInDatabase(lat, lng, category, normalizedKeyword, userLat, userLng);
        }
        if (searchResult.isEmpty()) {
            log.info("ES 매장 검색 결과 없음, DB 키워드 검색으로 대체: keyword={}, category={}", normalizedKeyword, category);
            return searchNearbyStoresInDatabase(lat, lng, category, normalizedKeyword, userLat, userLng);
        }

        // 2단계: DB에서 매장 데이터 일괄 로드
        List<Long> allIds = Stream.concat(
                searchResult.brandMatchIds().stream(),
                searchResult.nameMatchIds().stream()
        ).distinct().toList();

        List<Store> allStores = filterStoresMatchedToPartner(storeRepository.findAllByStoreIdInWithPartner(allIds));
        if (allStores.isEmpty()) {
            log.info("ES 매장 검색 ID가 DB에서 조회되지 않아 DB 키워드 검색으로 대체: keyword={}, category={}", normalizedKeyword, category);
            return searchNearbyStoresInDatabase(lat, lng, category, normalizedKeyword, userLat, userLng);
        }

        List<Long> partnerIds = allStores.stream()
                .map(store -> store.getPartner().getPartnerId())
                .distinct()
                .toList();

        Map<Long, List<BenefitCacheDto>> partnerToBenefitsMap = partnerBenefitCacheService.getBenefitsBatch(partnerIds);

        // storeId → DTO 맵 구성
        Map<Long, StoreDetailResponse> dtoMap = allStores.stream()
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
                            List<TierBenefitDto> tierBenefitDtos = toDistinctTierBenefits(finalBenefits);
                            return StoreDetailResponse.of(store, partner, tierBenefitDtos, distance);
                        }
                ));

        List<Long> strictBrandMatchIds = searchResult.brandMatchIds().stream()
                .filter(id -> isStrictPartnerKeywordMatch(dtoMap.get(id), normalizedKeyword))
                .toList();
        if (strictBrandMatchIds.isEmpty() && searchResult.nameMatchIds().isEmpty()) {
            log.info("ES 브랜드 검색 결과가 키워드와 정확히 맞지 않아 DB 키워드 검색으로 대체: keyword={}, category={}",
                    normalizedKeyword, category);
            return searchNearbyStoresInDatabase(lat, lng, category, normalizedKeyword, userLat, userLng);
        }

        // 브랜드 매치(partnerName) 그룹은 실제 파트너명이 키워드와 포함 관계일 때만 우선 노출한다.
        // ES/Nori match는 "팬시랜드"와 "서울랜드"처럼 공통 토큰(랜드)만으로도 매칭될 수 있으므로,
        // 검증되지 않은 브랜드 매치를 먼저 보여주면 무관한 혜택이 적용된 것처럼 보인다.
        return Stream.concat(
                strictBrandMatchIds.stream()
                        .map(dtoMap::get)
                        .filter(Objects::nonNull)
                        .sorted(Comparator.comparing(StoreDetailResponse::getDistance)),
                searchResult.nameMatchIds().stream()
                        .map(dtoMap::get)
                        .filter(Objects::nonNull)
                        .sorted(Comparator.comparing(StoreDetailResponse::getDistance))
        ).toList();
    }


    private List<Store> filterStoresMatchedToPartner(List<Store> stores) {
        if (stores == null || stores.isEmpty()) {
            return Collections.emptyList();
        }
        return stores.stream()
                .filter(this::isStoreMatchedToPartner)
                .toList();
    }

    private boolean isStoreMatchedToPartner(Store store) {
        if (store == null || store.getPartner() == null) {
            return false;
        }
        return isStoreMatchedToPartner(store.getStoreName(), store.getPartner().getPartnerName());
    }

    private boolean isStoreMatchedToPartner(StorePreviewProjection preview) {
        if (preview == null) {
            return false;
        }
        return isStoreMatchedToPartner(preview.getStoreName(), preview.getPartnerName());
    }

    private boolean isStoreMatchedToPartner(String storeName, String partnerName) {
        String normalizedStoreName = normalizeSearchText(storeName);
        return aliasesForPartner(partnerName).stream()
                .map(this::normalizeSearchText)
                .filter(alias -> !alias.isBlank())
                .anyMatch(normalizedStoreName::contains);
    }

    private List<String> aliasesForPartner(String partnerName) {
        String normalized = normalizeSearchText(partnerName);
        if ("gs25".equals(normalized) || "지에스25".equals(normalized)) {
            return List.of("GS25", "지에스25");
        }
        if ("cu".equals(normalized) || "씨유".equals(normalized)) {
            return List.of("CU", "씨유");
        }
        if ("세븐일레븐".equals(normalized) || "7eleven".equals(normalized)) {
            return List.of("세븐일레븐", "7-ELEVEN", "7ELEVEN");
        }
        return List.of(partnerName);
    }

    private boolean isStrictPartnerKeywordMatch(StoreDetailResponse response, String keyword) {
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
        if (text == null) {
            return "";
        }
        return text.toLowerCase(java.util.Locale.ROOT).replaceAll("[^가-힣a-z0-9]+", "");
    }


    private List<StoreDetailResponse> searchNearbyStoresInDatabase(double lat, double lng, String category,
                                                                   String keyword, double userLat, double userLng) {
        List<Long> fallbackStoreIds = storeRepository.searchNearbyStoreIds(lng, lat, category, keyword);
        List<Store> fallbackStores = findStoresWithPartnerInRequestedOrder(fallbackStoreIds);
        return toStoreDetailResponses(fallbackStores, userLat, userLng);
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
        List<StorePreviewProjection> matchedPreviews = previews.stream()
                .filter(this::isStoreMatchedToPartner)
                .toList();
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
                    return StoreDetailResponse.of(store, partner, tierBenefitDtos, distance);
                })
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<StoreDetailResponse> findNearbyByPartnerName(double lat, double lng, String partnerName, double userLat,
                                                        double userLng) {
        if (partnerName == null || partnerName.isBlank()) {
            throw new StoreKeywordException(StoreCode.PARTNERNAME_REQUEST);
        }

        Partner partner = partnerRepository.findByPartnerName(partnerName)
                .orElseThrow(() -> new PartnerNotFoundException(PartnerCode.PARTNER_NOT_FOUND));

        List<Long> storeIds = storeRepository.searchNearbyStoreIdsByPartnerId(lng, lat, partner.getPartnerId());
        List<Store> stores = findStoresWithPartnerInRequestedOrder(storeIds);

        // [변경] 기존: 루프 내부에서 매 매장(store)마다 benefitRepository.findAllByPartner_PartnerId()와
        //        tierBenefitRepository.findAllByBenefit_BenefitId()를 반복 호출 → 매장 수만큼 DB 쿼리 발생(N+1).
        // 변경 후: 루프 밖에서 캐시 서비스를 통해 해당 파트너의 혜택을 한 번만 조회.
        //         이후 루프에서는 DB 호출 없이 캐시 결과를 재사용.
        List<BenefitCacheDto> partnerBenefits = partnerBenefitCacheService.getBenefits(partner.getPartnerId());

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
