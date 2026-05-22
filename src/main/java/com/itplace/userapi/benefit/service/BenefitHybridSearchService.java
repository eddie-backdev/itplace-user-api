package com.itplace.userapi.benefit.service;

import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.entity.enums.MainCategory;
import com.itplace.userapi.benefit.entity.enums.UsageType;
import java.util.List;
import org.springframework.data.domain.Pageable;

public interface BenefitHybridSearchService {
    BenefitHybridSearchResult search(
            String keyword,
            MainCategory mainCategory,
            String category,
            UsageType filter,
            List<Carrier> carriers,
            Pageable pageable
    );
}
