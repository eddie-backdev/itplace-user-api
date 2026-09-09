package com.itplace.userapi.log.repository;

import com.itplace.userapi.log.dto.RankResult;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface CustomLogRepository {

    List<RankResult> findTopSearchRank(Instant from, Instant to);

    Map<String, List<String>> aggregateTopPartnerNamesByEvents(Long userId, Map<String, Integer> limits);

    Optional<Instant> findLatestLoggingAtByEvents(Long userId, List<String> events);

    Optional<String> findLatestParamByEvents(Long userId, List<String> events);

}
