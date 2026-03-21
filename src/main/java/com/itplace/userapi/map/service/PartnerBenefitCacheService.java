package com.itplace.userapi.map.service;

import com.itplace.userapi.benefit.entity.Benefit;
import com.itplace.userapi.benefit.entity.TierBenefit;
import com.itplace.userapi.benefit.repository.BenefitRepository;
import com.itplace.userapi.benefit.repository.TierBenefitRepository;
import com.itplace.userapi.map.dto.BenefitCacheDto;
import com.itplace.userapi.map.dto.response.TierBenefitDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PartnerBenefitCacheService {

    private final BenefitRepository benefitRepository;
    private final TierBenefitRepository tierBenefitRepository;

    @Cacheable(value = "partner-benefits", key = "#partnerId")
    @Transactional(readOnly = true)
    public List<BenefitCacheDto> getBenefits(Long partnerId) {
        List<Benefit> benefits = benefitRepository.findAllByPartner_PartnerId(partnerId);
        if (benefits.isEmpty()) {
            // List.of() 대신 new ArrayList<>() 사용.
            // 이유: List.of()는 final 클래스(ImmutableCollections$ListN)를 반환하므로,
            // Jackson NON_FINAL 타입 설정에서 타입 래퍼가 생략되어 역직렬화 실패.
            return new ArrayList<>();
        }

        List<TierBenefit> tierBenefits = tierBenefitRepository.findAllByBenefitIn(benefits);
        Map<Long, List<TierBenefit>> tierMap = tierBenefits.stream()
                .collect(Collectors.groupingBy(tb -> tb.getBenefit().getBenefitId()));

        // .toList() 대신 .collect(Collectors.toList()) 사용 (외부/내부 리스트 모두 동일).
        // 이유: Java 16+ Stream.toList()는 final 클래스를 반환하여 Jackson이 타입 래퍼를 붙이지 않음.
        // 결과적으로 [[element1], [element2]] 구조로 직렬화되고,
        // 역직렬화 시 타입 id 자리에 START_ARRAY 토큰이 오면서 MismatchedInputException 발생.
        // Collectors.toList()는 ArrayList(non-final)를 반환하므로 타입 래퍼가 정상적으로 생성됨.
        return benefits.stream()
                .map(b -> new BenefitCacheDto(
                        b.getBenefitId(),
                        b.getBenefitName(),
                        tierMap.getOrDefault(b.getBenefitId(), new ArrayList<>()).stream()
                                .map(t -> TierBenefitDto.builder()
                                        .grade(t.getGrade())
                                        .context(t.getContext())
                                        .build())
                                .collect(Collectors.toList())
                ))
                .collect(Collectors.toList());
    }
}
