package com.itplace.userapi.log.repository;

import com.itplace.userapi.log.dto.RankResult;
import java.time.Instant;
import java.util.List;

public interface CustomLogRepository {

    List<RankResult> findTopSearchRank(Instant from, Instant to);

    List<String> aggregateTopPartnerNamesByEvent(Long userId, String event, int topK);

}
