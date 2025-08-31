package com.itplace.userapi.log.service;

import com.itplace.userapi.log.dto.SearchRankResponse;
import java.util.List;

public interface LogService {
    void saveRequestLog(Long userId, String event, Long benefitId, String path, String param);

    void saveResponseLog(Long userId, String event, Long benefitId, Long partnerId, String path, String param);

    List<SearchRankResponse> searchRank(int recentDay, int prevDay);
}
