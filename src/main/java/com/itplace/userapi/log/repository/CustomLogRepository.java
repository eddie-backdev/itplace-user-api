package com.itplace.userapi.log.repository;

import com.itplace.userapi.log.dto.RankResult;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface CustomLogRepository {

    List<RankResult> findTopSearchRank(Instant from, Instant to);

    List<String> aggregateTopPartnerNamesByEvent(Long userId, String event, int topK);

    Optional<Instant> findLatestLoggingAtByEvents(Long userId, List<String> events);

    Optional<String> findLatestParamByEvents(Long userId, List<String> events);

}
