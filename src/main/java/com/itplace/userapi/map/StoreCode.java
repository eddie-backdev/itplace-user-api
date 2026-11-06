package com.itplace.userapi.map;

import com.itplace.userapi.common.BaseCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum StoreCode implements BaseCode {
    STORE_LIST_SUCCESS("STORE_LIST_SUCCESS", HttpStatus.OK, "사용자 위치 기반 매장 조회에 성공했습니다."),
    ADDRESS_LOOKUP_SUCCESS("ADDRESS_LOOKUP_SUCCESS", HttpStatus.OK, "좌표 기반 주소 조회에 성공했습니다."),
    LOCATION_SEARCH_SUCCESS("LOCATION_SEARCH_SUCCESS", HttpStatus.OK, "주소 또는 장소 검색에 성공했습니다."),
    MAP_VIEW_SUCCESS("MAP_VIEW_SUCCESS", HttpStatus.OK, "지도 매장 조회가 기록되었습니다."),
    DIRECTIONS_SUCCESS("DIRECTIONS_SUCCESS", HttpStatus.OK, "길찾기 조회에 성공했습니다."),
    PARAMETER_CHECK("PARAMETER_CHECK", HttpStatus.BAD_REQUEST, "잘못된 요청입니다."),
    KEYWORD_REQUEST("KEYWORD_REQUEST", HttpStatus.BAD_REQUEST, "키워드를 입력해주세요"),

    STORE_NOT_FOUND("STORE_NOT_FOUND", HttpStatus.NOT_FOUND, "지점을 찾을 수 없습니다."),
    DIRECTIONS_PROVIDER_UNAVAILABLE("DIRECTIONS_PROVIDER_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE, "길찾기 제공자를 사용할 수 없습니다."),
    DIRECTIONS_PROVIDER_TIMEOUT("DIRECTIONS_PROVIDER_TIMEOUT", HttpStatus.GATEWAY_TIMEOUT, "길찾기 제공자 응답 시간이 초과되었습니다."),
    DIRECTIONS_PROVIDER_ERROR("DIRECTIONS_PROVIDER_ERROR", HttpStatus.BAD_GATEWAY, "길찾기 제공자 응답을 처리할 수 없습니다."),
    DIRECTIONS_RATE_LIMITED("DIRECTIONS_RATE_LIMITED", HttpStatus.TOO_MANY_REQUESTS, "길찾기 요청이 많아요. 잠시 후 다시 시도해 주세요."),
    DIRECTIONS_INVALID_INPUT("DIRECTIONS_INVALID_INPUT", HttpStatus.BAD_REQUEST, "출발지와 목적지를 확인해 주세요."),
    LOCATION_SEARCH_PROVIDER_UNAVAILABLE("LOCATION_SEARCH_PROVIDER_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE, "주소 또는 장소 제공자를 사용할 수 없습니다."),
    LOCATION_SEARCH_PROVIDER_TIMEOUT("LOCATION_SEARCH_PROVIDER_TIMEOUT", HttpStatus.GATEWAY_TIMEOUT, "주소 또는 장소 제공자 응답 시간이 초과되었습니다."),
    LOCATION_SEARCH_PROVIDER_ERROR("LOCATION_SEARCH_PROVIDER_ERROR", HttpStatus.BAD_GATEWAY, "주소 또는 장소 제공자 응답을 처리할 수 없습니다."),
    LOCATION_SEARCH_RATE_LIMITED("LOCATION_SEARCH_RATE_LIMITED", HttpStatus.TOO_MANY_REQUESTS, "검색 요청이 많아요. 잠시 후 다시 시도해 주세요."),
    MAP_VIEW_INVALID_SESSION("MAP_VIEW_INVALID_SESSION", HttpStatus.BAD_REQUEST, "지도 조회 세션을 확인해 주세요."),
    MAP_VIEW_RATE_LIMITED("MAP_VIEW_RATE_LIMITED", HttpStatus.TOO_MANY_REQUESTS, "요청이 많아요. 잠시 후 다시 시도해 주세요."),
    MAP_VIEW_UNAVAILABLE("MAP_VIEW_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE, "지도 조회를 기록할 수 없습니다."),
    STORE_PARTNER_MISMATCH("STORE_PARTNER_MISMATCH", HttpStatus.BAD_REQUEST, "지점과 제휴사가 일치하지 않습니다."),

    PARTNERNAME_REQUEST("PARTNERNAME_REQUEST", HttpStatus.BAD_REQUEST, "제휴사명을 입력해주세요");

    private final String code;
    private final HttpStatus status;
    private final String message;
}
