package com.itplace.userapi.log.controller;

import com.itplace.userapi.common.ApiResponse;
import com.itplace.userapi.log.LogCode;
import com.itplace.userapi.log.dto.SearchRankResponse;
import com.itplace.userapi.log.service.LogService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class LogController {

    private final LogService logService;

    @GetMapping("/partners/search-ranking")
    public ResponseEntity<ApiResponse<?>> getPartnerRanking(
            @RequestParam(defaultValue = "2") int recentDay,
            @RequestParam(defaultValue = "3") int prevDay) {
        List<SearchRankResponse> searchRank = logService.searchRank(recentDay, prevDay);
        ApiResponse<?> body = ApiResponse.of(LogCode.PARTNERS_SEARCH_RANKING_SUCCESS, searchRank);

        return new ResponseEntity<>(body, body.getStatus());
    }
}
