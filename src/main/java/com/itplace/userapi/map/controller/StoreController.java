package com.itplace.userapi.map.controller;

import com.itplace.userapi.common.ApiResponse;
import com.itplace.userapi.map.StoreCode;
import com.itplace.userapi.map.dto.response.MapStorePreviewResponse;
import com.itplace.userapi.map.dto.response.MapStoreClusterResponse;
import com.itplace.userapi.map.dto.response.ReverseGeocodeResponse;
import com.itplace.userapi.map.dto.response.StoreDetailResponse;
import com.itplace.userapi.map.service.ReverseGeocodeService;
import com.itplace.userapi.map.service.StoreService;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@io.swagger.v3.oas.annotations.tags.Tag(name = "Map", description = "지도 기능 관련 API")
@RequestMapping("/api/v1/maps")
@RequiredArgsConstructor
@Validated
public class StoreController {
    private final StoreService storeService;
    private final ReverseGeocodeService reverseGeocodeService;

    // 좌표 기반 주소 조회
    @GetMapping("/address")
    public ResponseEntity<ApiResponse<ReverseGeocodeResponse>> getAddress(
            @RequestParam("lat") @DecimalMin("-90.0") @DecimalMax("90.0") double lat,
            @RequestParam("lng") @DecimalMin("-180.0") @DecimalMax("180.0") double lng
    ) {
        ReverseGeocodeResponse address = reverseGeocodeService.resolveAddress(lat, lng);
        ApiResponse<ReverseGeocodeResponse> body = ApiResponse.of(StoreCode.ADDRESS_LOOKUP_SUCCESS, address);

        return body.toResponseEntity();
    }



    // 현재 지도 화면 영역 기반 클러스터 목록 - 넓은 줌 레벨 전용 경량 응답
    @GetMapping("/stores/in-view/clusters")
    public ResponseEntity<ApiResponse<List<MapStoreClusterResponse>>> getStoresInViewClusters(
            @RequestParam("minLat") @DecimalMin("-90.0") @DecimalMax("90.0") double minLat,
            @RequestParam("minLng") @DecimalMin("-180.0") @DecimalMax("180.0") double minLng,
            @RequestParam("maxLat") @DecimalMin("-90.0") @DecimalMax("90.0") double maxLat,
            @RequestParam("maxLng") @DecimalMin("-180.0") @DecimalMax("180.0") double maxLng,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam("mapLevel") @Min(1) @Max(14) int mapLevel
    ) {
        List<MapStoreClusterResponse> clusters = storeService.findStoreClustersInView(
                minLat, minLng, maxLat, maxLng, category, mapLevel);
        ApiResponse<List<MapStoreClusterResponse>> body = ApiResponse.of(StoreCode.STORE_LIST_SUCCESS, clusters);

        return body.toResponseEntity();
    }

    // 현재 지도 화면 영역 기반 지점 목록 - 지도 카드 표시용 경량 응답
    @GetMapping("/stores/in-view/previews")
    public ResponseEntity<ApiResponse<List<MapStorePreviewResponse>>> getStoresInViewPreviews(
            @RequestParam("minLat") @DecimalMin("-90.0") @DecimalMax("90.0") double minLat,
            @RequestParam("minLng") @DecimalMin("-180.0") @DecimalMax("180.0") double minLng,
            @RequestParam("maxLat") @DecimalMin("-90.0") @DecimalMax("90.0") double maxLat,
            @RequestParam("maxLng") @DecimalMin("-180.0") @DecimalMax("180.0") double maxLng,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam("userLat") @DecimalMin("-90.0") @DecimalMax("90.0") double userLat,
            @RequestParam("userLng") @DecimalMin("-180.0") @DecimalMax("180.0") double userLng,
            @RequestParam(value = "limit", defaultValue = "500") @Min(1) @Max(2000) int limit,
            @RequestParam(value = "includeBenefits", defaultValue = "true") boolean includeBenefits
    ) {
        List<MapStorePreviewResponse> stores = storeService.findStoresInViewPreviews(
                minLat, minLng, maxLat, maxLng, category, userLat, userLng, limit, includeBenefits);
        ApiResponse<List<MapStorePreviewResponse>> body = ApiResponse.of(StoreCode.STORE_LIST_SUCCESS, stores);

        return body.toResponseEntity();
    }

    // 사용자 위치 기반 전체 지점 목록 - 지도 카드 표시용 경량 응답
    @GetMapping("/nearby/previews")
    public ResponseEntity<ApiResponse<List<MapStorePreviewResponse>>> getNearbyPreviews(
            @RequestParam("lat") @DecimalMin("-90.0") @DecimalMax("90.0") double lat,
            @RequestParam("lng") @DecimalMin("-180.0") @DecimalMax("180.0") double lng,
            @RequestParam("radiusMeters") @DecimalMin("1.0") @DecimalMax("400000.0") double radiusMeters,
            @RequestParam("userLat") @DecimalMin("-90.0") @DecimalMax("90.0") double userLat,
            @RequestParam("userLng") @DecimalMin("-180.0") @DecimalMax("180.0") double userLng
    ) {
        List<MapStorePreviewResponse> stores = storeService.findNearbyPreviews(lat, lng, radiusMeters, userLat, userLng);
        ApiResponse<List<MapStorePreviewResponse>> body = ApiResponse.of(StoreCode.STORE_LIST_SUCCESS, stores);

        return body.toResponseEntity();
    }

    // 사용자 위치 기반 전체 지점 목록
    @GetMapping("/nearby")
    public ResponseEntity<ApiResponse<List<StoreDetailResponse>>> getNearby(
            @RequestParam("lat") @DecimalMin("-90.0") @DecimalMax("90.0") double lat,
            @RequestParam("lng") @DecimalMin("-180.0") @DecimalMax("180.0") double lng,
            @RequestParam("radiusMeters") @DecimalMin("1.0") @DecimalMax("400000.0") double radiusMeters,
            @RequestParam("userLat") @DecimalMin("-90.0") @DecimalMax("90.0") double userLat,
            @RequestParam("userLng") @DecimalMin("-180.0") @DecimalMax("180.0") double userLng
    ) {
        List<StoreDetailResponse> stores = storeService.findNearby(lat, lng, radiusMeters, userLat, userLng);
        ApiResponse<List<StoreDetailResponse>> body = ApiResponse.of(StoreCode.STORE_LIST_SUCCESS, stores);

        return body.toResponseEntity();
    }


    // 사용자 위치 기반 특정 카테고리 지점 목록 - 지도 카드 표시용 경량 응답
    @GetMapping("/nearby/category/previews")
    public ResponseEntity<ApiResponse<List<MapStorePreviewResponse>>> getNearbyCategoryPreviews(
            @RequestParam("lat") @DecimalMin("-90.0") @DecimalMax("90.0") double lat,
            @RequestParam("lng") @DecimalMin("-180.0") @DecimalMax("180.0") double lng,
            @RequestParam("radiusMeters") @DecimalMin("1.0") @DecimalMax("400000.0") double radiusMeters,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam("userLat") @DecimalMin("-90.0") @DecimalMax("90.0") double userLat,
            @RequestParam("userLng") @DecimalMin("-180.0") @DecimalMax("180.0") double userLng
    ) {
        List<MapStorePreviewResponse> stores = storeService.findNearbyByCategoryPreviews(
                lat, lng, radiusMeters, category, userLat, userLng);
        ApiResponse<List<MapStorePreviewResponse>> body = ApiResponse.of(StoreCode.STORE_LIST_SUCCESS, stores);

        return body.toResponseEntity();
    }

    // 사용자 위치 기반 특정 카테고리 지점 목록
    @GetMapping("/nearby/category")
    public ResponseEntity<ApiResponse<List<StoreDetailResponse>>> getNearbyCategory(
            @RequestParam("lat") @DecimalMin("-90.0") @DecimalMax("90.0") double lat,
            @RequestParam("lng") @DecimalMin("-180.0") @DecimalMax("180.0") double lng,
            @RequestParam("radiusMeters") @DecimalMin("1.0") @DecimalMax("400000.0") double radiusMeters,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam("userLat") @DecimalMin("-90.0") @DecimalMax("90.0") double userLat,
            @RequestParam("userLng") @DecimalMin("-180.0") @DecimalMax("180.0") double userLng
    ) {
        List<StoreDetailResponse> stores = storeService.findNearbyByCategory(lat, lng, radiusMeters, category, userLat,
                userLng);
        ApiResponse<List<StoreDetailResponse>> body = ApiResponse.of(StoreCode.STORE_LIST_SUCCESS, stores);

        return body.toResponseEntity();
    }


    // 사용자 위치 기반 키워드 검색한 지점 목록 - 지도 카드 표시용 경량 응답
    @GetMapping("/nearby/search/previews")
    public ResponseEntity<ApiResponse<List<MapStorePreviewResponse>>> getNearbySearchPreviews(
            @RequestParam("lat") @DecimalMin("-90.0") @DecimalMax("90.0") double lat,
            @RequestParam("lng") @DecimalMin("-180.0") @DecimalMax("180.0") double lng,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam("keyword") String keyword,
            @RequestParam("userLat") @DecimalMin("-90.0") @DecimalMax("90.0") double userLat,
            @RequestParam("userLng") @DecimalMin("-180.0") @DecimalMax("180.0") double userLng
    ) {
        List<MapStorePreviewResponse> stores = storeService.findNearbyByKeywordPreviews(
                lat, lng, category, keyword, userLat, userLng);
        ApiResponse<List<MapStorePreviewResponse>> body = ApiResponse.of(StoreCode.STORE_LIST_SUCCESS, stores);

        return body.toResponseEntity();
    }

    // 사용자 위치 기반 키워드 검색한 지점 목록
    @GetMapping("/nearby/search")
    public ResponseEntity<ApiResponse<List<StoreDetailResponse>>> getNearbySearch(
            @RequestParam("lat") @DecimalMin("-90.0") @DecimalMax("90.0") double lat,
            @RequestParam("lng") @DecimalMin("-180.0") @DecimalMax("180.0") double lng,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam("keyword") String keyword,
            @RequestParam("userLat") @DecimalMin("-90.0") @DecimalMax("90.0") double userLat,
            @RequestParam("userLng") @DecimalMin("-180.0") @DecimalMax("180.0") double userLng
    ) {
        List<StoreDetailResponse> stores = storeService.findNearbyByKeyword(lat, lng, category, keyword, userLat, userLng);
        ApiResponse<List<StoreDetailResponse>> body = ApiResponse.of(StoreCode.STORE_LIST_SUCCESS, stores);

        return body.toResponseEntity();
    }

    @GetMapping("/nearby/itplace-ai")
    public ResponseEntity<ApiResponse<List<StoreDetailResponse>>> getNearbyByPartner(
            @RequestParam("lat") @DecimalMin("-90.0") @DecimalMax("90.0") double lat,
            @RequestParam("lng") @DecimalMin("-180.0") @DecimalMax("180.0") double lng,
            @RequestParam("partnerName") String partnerName,
            @RequestParam("userLat") @DecimalMin("-90.0") @DecimalMax("90.0") double userLat,
            @RequestParam("userLng") @DecimalMin("-180.0") @DecimalMax("180.0") double userLng
    ) {
        List<StoreDetailResponse> stores = storeService.findNearbyByPartnerName(lat, lng, partnerName, userLat, userLng);
        ApiResponse<List<StoreDetailResponse>> body = ApiResponse.of(StoreCode.STORE_LIST_SUCCESS, stores);

        return body.toResponseEntity();
    }
}
