package com.itplace.userapi.favorite.enums;

import com.itplace.userapi.common.BaseCode;
import org.springframework.http.HttpStatus;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum FavoriteCode implements BaseCode {
    FAVORITE_ADD_SUCCESS("FAVORITE_ADD_SUCCESS", HttpStatus.OK, "즐겨찾기 등록 성공"),
    FAVORITE_DELETE_SUCCESS("FAVORITE_DELETE_SUCCESS", HttpStatus.OK, "즐겨찾기 삭제 성공"),
    FAVORITE_BENEFIT_SUCCESS("FAVORITE_BENEFIT_SUCCESS", HttpStatus.OK, "즐겨찾기 목록 조회 성공"),
    FAVORITE_BENEFIT_SEARCH_SUCCESS("FAVORITE_BENEFIT_SEARCH_SUCCESS", HttpStatus.OK, "즐겨찾기 목록 검색 성공"),
    FAVORITE_BENEFIT_DETAIL_SUCCESS("FAVORITE_BENEFIT_DETAIL_SUCCESS", HttpStatus.OK, "즐겨찾기 혜택 상세 조회 성공"),
    FAVORITE_ALREADY_EXISTS("FAVORITE_ALREADY_EXISTS", HttpStatus.CONFLICT, "이미 즐겨찾기에 등록된 혜택입니다.");

    private final String code;
    private final HttpStatus status;
    private final String message;
}

