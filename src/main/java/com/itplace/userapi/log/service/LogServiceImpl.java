package com.itplace.userapi.log.service;

import com.itplace.userapi.benefit.entity.Benefit;
import com.itplace.userapi.benefit.repository.BenefitRepository;
import com.itplace.userapi.log.dto.RankResult;
import com.itplace.userapi.log.dto.SearchRankResponse;
import com.itplace.userapi.log.entity.LogDocument;
import com.itplace.userapi.log.repository.LogRepository;
import com.itplace.userapi.partner.entity.Partner;
import com.itplace.userapi.partner.repository.PartnerRepository;
import jakarta.transaction.Transactional;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class LogServiceImpl implements LogService {

    private final LogRepository logRepository;
    private final BenefitRepository benefitRepository;
    private final PartnerRepository partnerRepository;

    // 클릭
    @Override
    public void saveRequestLog(Long userId, String event, Long benefitId, String path, String param) {
        Optional<Benefit> benefitOpt = benefitRepository.findById(benefitId);
        if (benefitOpt.isEmpty()) {
            return;
        }
        Benefit benefit = benefitOpt.get();

        Long partnerId = benefit.getPartner().getPartnerId();
        String partnerName = benefit.getPartner().getPartnerName();

        log.info("REQUEST: {}, path={}, event={}, benefitId={}, partnerId={}",
                userId, path, event, benefitId, partnerId);

        LogDocument logDocument = LogDocument.builder()
                .userId(userId)
                .event(event)
                .benefitId(benefitId)
                .benefitName(benefit.getBenefitName())
                .partnerId(partnerId)
                .partnerName(partnerName)
                .path(path)
                .param(param)
                .loggingAt(Instant.now())
                .build();
        logRepository.save(logDocument);
    }

    // 검색, 상세
    @Override
    public void saveResponseLog(Long userId, String event, Long benefitId, Long partnerId, String path, String param) {
        Optional<Benefit> benefitOpt = benefitRepository.findById(benefitId);
        if (benefitOpt.isEmpty()) {
            return;
        }
        Benefit benefit = benefitOpt.get();
        String partnerName = benefit.getPartner().getPartnerName();

        log.info("RESPONSE: {}, path={}, event={}, benefitId={}, partnerId={}",
                userId, path, event, benefitId, partnerId);

        LogDocument logDocument = LogDocument.builder()
                .userId(userId)
                .event(event)
                .benefitId(benefitId)
                .benefitName(benefit.getBenefitName())
                .partnerId(partnerId)
                .partnerName(partnerName)
                .path(path)
                .param(param)
                .loggingAt(Instant.now())
                .build();
        logRepository.save(logDocument);
    }

    @Override
    public List<SearchRankResponse> searchRank(int recentDay, int prevDay) {
        Instant now = Instant.now();
        Instant from = now.minus(Duration.ofDays(recentDay));
        Instant prevFrom = from.minus(Duration.ofDays(prevDay));
        Instant prevTo = from;

        List<RankResult> recentRanks = logRepository.findTopSearchRank(from, now);
        List<RankResult> prevRanks = logRepository.findTopSearchRank(prevFrom, prevTo);

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
}
