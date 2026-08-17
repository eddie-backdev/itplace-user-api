package com.itplace.userapi.log.service;

import com.itplace.userapi.log.dto.ResponseLogCommand;
import com.itplace.userapi.log.dto.response.SearchRankResponse;
import java.util.List;

public interface LogService {
    void saveRequestLog(Long userId, String event, Long benefitId, String path, String param);

    void saveResponseLogs(Long userId, List<ResponseLogCommand> commands);

    List<SearchRankResponse> searchRank(int recentDay, int prevDay);
}
