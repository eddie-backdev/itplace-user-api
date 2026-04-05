package com.itplace.userapi.map.controller;

import com.itplace.userapi.common.ApiResponse;
import com.itplace.userapi.map.StoreCode;
import com.itplace.userapi.map.dto.response.StoreDetailResponse;
import com.itplace.userapi.map.service.StoreService;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/maps")
@RequiredArgsConstructor
@Validated
public class StoreController {
    private final StoreService storeService;

    // 사용자 위치 기반 전체 지점 목록
    @GetMapping("/nearby")
    public ResponseEntity<ApiResponse<?>> getNearby(
            @RequestParam("lat") @DecimalMin("-90.0") @DecimalMax("90.0") double lat,
            @RequestParam("lng") @DecimalMin("-180.0") @DecimalMax("180.0") double lng,
            @RequestParam("radiusMeters") @DecimalMin("1.0") @DecimalMax("50000.0") double radiusMeters,
            @RequestParam("userLat") @DecimalMin("-90.0") @DecimalMax("90.0") double userLat,
            @RequestParam("userLng") @DecimalMin("-180.0") @DecimalMax("180.0") double userLng
    ) {
        List<StoreDetailResponse> stores = storeService.findNearby(lat, lng, radiusMeters, userLat, userLng);
        ApiResponse<?> body = ApiResponse.of(StoreCode.STORE_LIST_SUCCESS, stores);

        return new ResponseEntity<>(body, body.getStatus());
    }

    // 사용자 위치 기반 특정 카테고리 지점 목록
    @GetMapping("/nearby/category")
    public ResponseEntity<ApiResponse<?>> getNearbyCategory(
            @RequestParam("lat") @DecimalMin("-90.0") @DecimalMax("90.0") double lat,
            @RequestParam("lng") @DecimalMin("-180.0") @DecimalMax("180.0") double lng,
            @RequestParam("radiusMeters") @DecimalMin("1.0") @DecimalMax("50000.0") double radiusMeters,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam("userLat") @DecimalMin("-90.0") @DecimalMax("90.0") double userLat,
            @RequestParam("userLng") @DecimalMin("-180.0") @DecimalMax("180.0") double userLng
    ) {
        List<StoreDetailResponse> stores = storeService.findNearbyByCategory(lat, lng, radiusMeters, category, userLat,
                userLng);
        ApiResponse<?> body = ApiResponse.of(StoreCode.STORE_LIST_SUCCESS, stores);

        return new ResponseEntity<>(body, body.getStatus());
    }

    // 사용자 위치 기반 키워드 검색한 지점 목록
    @GetMapping("/nearby/search")
    public ResponseEntity<ApiResponse<?>> getNearbySearch(
            @RequestParam("lat") @DecimalMin("-90.0") @DecimalMax("90.0") double lat,
            @RequestParam("lng") @DecimalMin("-180.0") @DecimalMax("180.0") double lng,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam("keyword") String keyword,
            @RequestParam("userLat") @DecimalMin("-90.0") @DecimalMax("90.0") double userLat,
            @RequestParam("userLng") @DecimalMin("-180.0") @DecimalMax("180.0") double userLng
    ) {
        List<StoreDetailResponse> stores = storeService.findNearbyByKeyword(lat, lng, category, keyword, userLat, userLng);
        ApiResponse<?> body = ApiResponse.of(StoreCode.STORE_LIST_SUCCESS, stores);

        return new ResponseEntity<>(body, body.getStatus());
    }

    @GetMapping("/nearby/itplace-ai")
    public ResponseEntity<ApiResponse<?>> getNearbyByPartner(
            @RequestParam("lat") @DecimalMin("-90.0") @DecimalMax("90.0") double lat,
            @RequestParam("lng") @DecimalMin("-180.0") @DecimalMax("180.0") double lng,
            @RequestParam("partnerName") String partnerName,
            @RequestParam("userLat") @DecimalMin("-90.0") @DecimalMax("90.0") double userLat,
            @RequestParam("userLng") @DecimalMin("-180.0") @DecimalMax("180.0") double userLng
    ) {
        List<StoreDetailResponse> stores = storeService.findNearbyByPartnerName(lat, lng, partnerName, userLat, userLng);
        ApiResponse<?> body = ApiResponse.of(StoreCode.STORE_LIST_SUCCESS, stores);

        return ResponseEntity.status(body.getStatus()).body(body);
    }
}
