package com.itplace.userapi.log.service;

import com.itplace.userapi.benefit.entity.Benefit;
import com.itplace.userapi.benefit.repository.BenefitRepository;
import com.itplace.userapi.log.dto.RankResult;
import com.itplace.userapi.log.dto.ResponseLogCommand;
import com.itplace.userapi.log.dto.response.SearchRankResponse;
import com.itplace.userapi.log.entity.LogDocument;
import com.itplace.userapi.log.repository.LogRepository;
import com.itplace.userapi.partner.entity.Partner;
import com.itplace.userapi.partner.repository.PartnerRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class LogServiceImpl implements LogService {

    private final LogRepository logRepository;
    private final BenefitRepository benefitRepository;
    private final PartnerRepository partnerRepository;

    // 검색, 상세, 관심 혜택
    @Override
    @Async("logTaskExecutor")
    public void saveResponseLogs(Long userId, List<ResponseLogCommand> commands) {
        if (userId == null || commands == null || commands.isEmpty()) {
            return;
        }

        try {
            List<Long> benefitIds = commands.stream()
                    .map(ResponseLogCommand::benefitId)
                    .filter(java.util.Objects::nonNull)
                    .distinct()
                    .toList();
            if (benefitIds.isEmpty()) {
                return;
            }

            Map<Long, Benefit> benefitById = benefitRepository.findAllByIdWithPartner(benefitIds).stream()
                    .collect(Collectors.toMap(Benefit::getBenefitId, Function.identity()));
            Instant loggingAt = Instant.now();
            List<LogDocument> documents = commands.stream()
                    .map(command -> toResponseLogDocument(userId, command, benefitById.get(command.benefitId()), loggingAt))
                    .filter(java.util.Objects::nonNull)
                    .toList();
            if (documents.isEmpty()) {
                return;
            }

            logRepository.saveAll(documents);
            log.debug("응답 로그 일괄 저장 완료: userId={}, count={}", userId, documents.size());
        } catch (RuntimeException e) {
            log.warn("응답 로그 일괄 저장 실패: userId={}, requestedCount={}", userId, commands.size(), e);
        }
    }

    private LogDocument toResponseLogDocument(
            Long userId,
            ResponseLogCommand command,
            Benefit benefit,
            Instant loggingAt
    ) {
        if (benefit == null) {
            return null;
        }

        Partner partner = benefit.getPartner();
        Long partnerId = command.partnerId() != null
                ? command.partnerId()
                : partner == null ? null : partner.getPartnerId();
        return LogDocument.builder()
                .userId(userId)
                .event(command.event())
                .benefitId(command.benefitId())
                .benefitName(benefit.getBenefitName())
                .partnerId(partnerId)
                .partnerName(partner == null ? null : partner.getPartnerName())
                .path(command.path())
                .param(command.param())
                .loggingAt(loggingAt)
                .build();
    }

    @Override
    public List<SearchRankResponse> searchRank(int recentDay, int prevDay) {
        Instant now = Instant.now();
        Instant from = now.minus(Duration.ofDays(recentDay));
        Instant prevFrom = from.minus(Duration.ofDays(prevDay));
        Instant prevTo = from;

        List<RankResult> recentRanks = safeFindTopSearchRank(from, now);
        List<RankResult> prevRanks = safeFindTopSearchRank(prevFrom, prevTo);

        Map<Long, Long> prevRankMap = new HashMap<>();
        long rnk = 1;
        for (RankResult prevRank : prevRanks.stream()
                .sorted(Comparator.comparing(RankResult::getCount).reversed())
                .toList()) {
            prevRankMap.put(prevRank.getId(), rnk++);
        }

        AtomicLong rankCount = new AtomicLong(1);

        return recentRanks.stream().map(r -> {
            String partnerName = partnerRepository.findById(r.getId())
                    .map(Partner::getPartnerName)
                    .orElse(null);
            long rank = rankCount.getAndIncrement();

            Long prevRank = prevRankMap.getOrDefault(r.getId(), 0L);
            log.debug("prevRank: {}", prevRank);

            long rankChange = prevRank == 0 ? 0 : prevRank - rank;

            String changeDirection;
            if (prevRank == 0L) {
                changeDirection = "NEW";
            } else if (rankChange > 0) {
                changeDirection = "UP";
            } else if (rankChange < 0) {
                changeDirection = "DOWN";
            } else {
                changeDirection = "SAME";
            }

            return new SearchRankResponse(
                    partnerName,
                    r.getCount(),
                    rank,
                    prevRank == 0L ? 99999 : prevRank,
                    rankChange,
                    changeDirection);
        }).toList();
    }

    private List<RankResult> safeFindTopSearchRank(Instant from, Instant to) {
        try {
            return logRepository.findTopSearchRank(from, to);
        } catch (RuntimeException e) {
            log.warn("검색 랭킹 집계에 실패해 빈 목록으로 대체합니다. from={}, to={}", from, to, e);
            return List.of();
        }
    }
}
