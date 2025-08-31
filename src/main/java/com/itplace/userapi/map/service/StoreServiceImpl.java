package com.itplace.userapi.map.service;

import com.itplace.userapi.benefit.entity.Benefit;
import com.itplace.userapi.benefit.entity.TierBenefit;
import com.itplace.userapi.benefit.repository.BenefitRepository;
import com.itplace.userapi.benefit.repository.TierBenefitRepository;
import com.itplace.userapi.map.StoreCode;
import com.itplace.userapi.map.dto.StoreDetailDto;
import com.itplace.userapi.map.dto.TierBenefitDto;
import com.itplace.userapi.map.entity.Store;
import com.itplace.userapi.map.exception.StoreKeywordException;
import com.itplace.userapi.map.repository.StoreRepository;
import com.itplace.userapi.partner.PartnerCode;
import com.itplace.userapi.partner.entity.Partner;
import com.itplace.userapi.partner.exception.PartnerNotFoundException;
import com.itplace.userapi.partner.repository.PartnerRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
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
    private final BenefitRepository benefitRepository;
    private final TierBenefitRepository tierBenefitRepository;
    private final PartnerRepository partnerRepository;

    private static final int GRID_SIZE = 10; // 10x10 그리드
    private static final int STORES_PER_CELL = 5; // 각 셀에서 가져올 상점 수
    private static final int FINAL_LIMIT = 300; // 최종 반환할 상점 수

    private static final int WIDE_RADIUS_THRESHOLD = 10000; // 10km

    @Override
    @Transactional(readOnly = true)
    public List<StoreDetailDto> findNearby(double lat, double lng, double radiusMeters, double userLat,
                                           double userLng) {

        List<Long> allStoreIds;

        // 1. 반경에 따라 전략 선택
        if (radiusMeters <= WIDE_RADIUS_THRESHOLD) {
            // 좁은 반경: 단일 쿼리로 모든 ID 조회 후 앱에서 셔플
            log.info("좁은 반경 검색 실행 ({}m)", radiusMeters);
            allStoreIds = findNearbyWithSingleQuery(lat, lng, radiusMeters);
        } else {
            // 넓은 반경: 그리드 기반 샘플링
            log.info("넓은 반경 검색 실행 ({}m)", radiusMeters);
            allStoreIds = findNearbyWithGridSampling(lat, lng, radiusMeters);
        }

        log.info("============ 샘플링된 전체 store ID 개수: {} =============", allStoreIds.size());

        if (allStoreIds.isEmpty()) {
            return Collections.emptyList();
        }

        // 2. 최종 150개 선택 및 상세 정보 조회
        Collections.shuffle(allStoreIds);
        List<Long> limitedStoreIds = allStoreIds.subList(0, Math.min(allStoreIds.size(), FINAL_LIMIT));
        List<Store> limitedStores = storeRepository.findAllById(limitedStoreIds);

        // --- (이하 N+1 문제 해결 로직은 기존과 동일) ---
        List<Long> partnerIds = limitedStores.stream()
                .map(store -> store.getPartner().getPartnerId())
                .distinct()
                .toList();

        List<Benefit> allBenefits = benefitRepository.findAllByPartner_PartnerIdIn(partnerIds);
        List<TierBenefit> allTierBenefits = tierBenefitRepository.findAllByBenefitIn(allBenefits);

        Map<Long, List<Benefit>> partnerToBenefitsMap = allBenefits.stream()
                .collect(Collectors.groupingBy(b -> b.getPartner().getPartnerId()));

        Map<Long, List<TierBenefit>> benefitToTiersMap = allTierBenefits.stream()
                .collect(Collectors.groupingBy(tb -> tb.getBenefit().getBenefitId()));

        return limitedStores.stream()
                .map(store -> {
                    Partner partner = store.getPartner();
                    List<Benefit> benefitsForPartner = partnerToBenefitsMap.getOrDefault(partner.getPartnerId(), Collections.emptyList());
                    List<Benefit> finalBenefits = selectBenefits(benefitsForPartner, store.getStoreName());

                    List<TierBenefitDto> tierBenefitDtos = finalBenefits.stream()
                            .flatMap(benefit ->
                                    benefitToTiersMap.getOrDefault(benefit.getBenefitId(), Collections.emptyList()).stream()
                                            .map(tierBenefit -> TierBenefitDto.builder()
                                                    .grade(tierBenefit.getGrade())
                                                    .context(tierBenefit.getContext())
                                                    .build())
                            )
                            .toList();

                    double distance = calculateDistance(userLat, userLng, store.getLocation().getY(),
                            store.getLocation().getX());

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
                        storeRepository.findRandomStoreIdsInBounds(cellMinLat, cellMaxLat, cellMinLng, cellMaxLng, STORES_PER_CELL)
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
        // "전체", null, 빈 문자열이면 findNearby의 로직을 따름
        if (category == null || category.isBlank() || category.equalsIgnoreCase("전체")) {
            return findNearby(lat, lng, radiusMeters, userLat, userLng);
        }

        log.info("카테고리 기반 반경 검색 실행: {}, 반경: {}m", category, radiusMeters);

        // 1. DB에서 직접 카테고리와 반경으로 필터링된 무작위 상점 목록 조회
        List<Store> limitedStores = storeRepository.findRandomStoresByCategory(category, lat, lng, radiusMeters, FINAL_LIMIT);

        if (limitedStores.isEmpty()) {
            return Collections.emptyList();
        }

        // --- (이하 N+1 문제 해결 및 DTO 변환 로직) ---
        List<Long> partnerIds = limitedStores.stream()
                .map(store -> store.getPartner().getPartnerId())
                .distinct()
                .toList();

        List<Benefit> allBenefits = benefitRepository.findAllByPartner_PartnerIdIn(partnerIds);
        List<TierBenefit> allTierBenefits = tierBenefitRepository.findAllByBenefitIn(allBenefits);

        Map<Long, List<Benefit>> partnerToBenefitsMap = allBenefits.stream()
                .collect(Collectors.groupingBy(b -> b.getPartner().getPartnerId()));

        Map<Long, List<TierBenefit>> benefitToTiersMap = allTierBenefits.stream()
                .collect(Collectors.groupingBy(tb -> tb.getBenefit().getBenefitId()));

        return limitedStores.stream()
                .map(store -> {
                    Partner partner = store.getPartner();
                    List<Benefit> benefitsForPartner = partnerToBenefitsMap.getOrDefault(partner.getPartnerId(), Collections.emptyList());
                    List<Benefit> finalBenefits = selectBenefits(benefitsForPartner, store.getStoreName());

                    List<TierBenefitDto> tierBenefitDtos = finalBenefits.stream()
                            .flatMap(benefit ->
                                    benefitToTiersMap.getOrDefault(benefit.getBenefitId(), Collections.emptyList()).stream()
                                            .map(tierBenefit -> TierBenefitDto.builder()
                                                    .grade(tierBenefit.getGrade())
                                                    .context(tierBenefit.getContext())
                                                    .build())
                            )
                            .toList();

                    double distance = calculateDistance(userLat, userLng, store.getLocation().getY(),
                            store.getLocation().getX());

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

        List<Store> stores = storeRepository.searchNearbyStores(lng, lat, category, keyword);

        if (stores.isEmpty()) {
            return Collections.emptyList();
        }

        // 파트너 ID 수집
        List<Long> partnerIds = stores.stream()
                .map(store -> store.getPartner().getPartnerId())
                .distinct()
                .toList();

        // 혜택 일괄 조회
        List<Benefit> allBenefits = benefitRepository.findAllByPartner_PartnerIdIn(partnerIds);
        List<TierBenefit> allTierBenefits = tierBenefitRepository.findAllByBenefitIn(allBenefits);

        // 맵 구성
        Map<Long, List<Benefit>> partnerToBenefitsMap = allBenefits.stream()
                .collect(Collectors.groupingBy(b -> b.getPartner().getPartnerId()));
        Map<Long, List<TierBenefit>> benefitToTiersMap = allTierBenefits.stream()
                .collect(Collectors.groupingBy(tb -> tb.getBenefit().getBenefitId()));
        // 선택 결과 캐시 (partnerId + storeName 기준) — 같은 파트너/상점 조합에 대해 재사용
        Map<String, List<TierBenefitDto>> benefitDtoCache = new java.util.HashMap<>();

        return stores.stream()
                .map(store -> {
                    Partner partner = store.getPartner();
                    double storeLat = store.getLocation().getY();
                    double storeLng = store.getLocation().getX();
                    double distance = userLat == 0 || userLng == 0
                            ? 0 : calculateDistance(userLat, userLng, storeLat, storeLng);

                    List<Benefit> benefitsForPartner = partnerToBenefitsMap.getOrDefault(partner.getPartnerId(), Collections.emptyList());
                    List<Benefit> finalBenefits = selectBenefits(benefitsForPartner, store.getStoreName());
                    List<TierBenefitDto> tierBenefitDtos = finalBenefits.stream()
                            .flatMap(benefit ->
                                    tierBenefitRepository.findAllByBenefit_BenefitId(benefit.getBenefitId()).stream()
                                            .map(tierBenefit -> TierBenefitDto.builder()
                                                    .grade(tierBenefit.getGrade())
                                                    .context(tierBenefit.getContext())
                                                    .build())
                            )
                            .toList();
                    return StoreDetailDto.of(store, partner, tierBenefitDtos, distance);
                })
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<StoreDetailDto> findNearbyByPartnerName(double lat, double lng, String partnerName, double userLat,
                                                        double userLng) {
        if (partnerName == null || partnerName.isBlank()) {
            throw new StoreKeywordException(StoreCode.PARTNERNAME_REQUEST);
        }

        // partnerName으로 partnerId 조회
        Partner partner = partnerRepository.findByPartnerName(partnerName)
                .orElseThrow(() -> new PartnerNotFoundException(PartnerCode.PARTNER_NOT_FOUND));

        // partnerId로 매장 검색
        List<Store> stores = storeRepository.searchNearbyStoresByPartnerId(lng, lat, partner.getPartnerId());

        return stores.stream()
                .map(store -> {
                    double storeLat = store.getLocation().getY();
                    double storeLng = store.getLocation().getX();
                    double distance = calculateDistance(userLat, userLng, storeLat, storeLng);

                    // 제휴사 혜택 조회
                    List<Benefit> benefits = benefitRepository.findAllByPartner_PartnerId(partner.getPartnerId());
                    List<Benefit> finalBenefits = selectBenefits(benefits, store.getStoreName());

                    // tierBenefit 매핑
                    List<TierBenefitDto> tierBenefitDtos = finalBenefits.stream()
                            .flatMap(benefit ->
                                    tierBenefitRepository.findAllByBenefit_BenefitId(benefit.getBenefitId()).stream()
                                            .map(tierBenefit -> TierBenefitDto.builder()
                                                    .grade(tierBenefit.getGrade())
                                                    .context(tierBenefit.getContext())
                                                    .build())
                            )
                            .toList();

                    return StoreDetailDto.of(store, partner, tierBenefitDtos, distance);
                })
                .toList();
    }

    private double calculateDistance(double userLat, double userLng, double storeLat, double storeLng) {
        final int earthRadius = 6378137; // 미터

        double dLat = Math.toRadians(storeLat - userLat);
        double dLng = Math.toRadians(storeLng - userLng);

        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(userLat))
                * Math.cos(Math.toRadians(storeLat))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

        double d = earthRadius * c * 0.001; // km 단위 거리
        return Math.round(d * 10) / 10.0;
    }

    private List<Benefit> selectBenefits(List<Benefit> benefits, String storeName) {
        if (benefits == null || benefits.isEmpty()) {
            return Collections.emptyList();
        }

        // 오프라인 우선
        return benefits.stream()
                .filter(b -> b.getBenefitName().contains("오프라인"))
                .findFirst()
                .map(List::of)
                .orElseGet(() -> {
                    if (benefits.size() >= 3) {
                        List<Benefit> matched = benefits.stream()
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


