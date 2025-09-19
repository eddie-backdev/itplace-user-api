package com.itplace.userapi.log;

import com.itplace.userapi.common.BaseCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum LogCode implements BaseCode {
    BENEFITS_MOST_CLICKED_SUCCESS("BENEFITS_MOST_CLICKED_SUCCESS", HttpStatus.OK, "자주 클릭한 제휴처가 성공적으로 조회되었습니다."),
    PARTNERS_SEARCH_RANKING_SUCCESS("PARTNERS_SEARCH_RANKING_SUCCESS", HttpStatus.OK, "제휴처 검색 순위가 성공적으로 조회되었습니다.");

    private final String code;
    private final HttpStatus status;
    private final String message;
}
