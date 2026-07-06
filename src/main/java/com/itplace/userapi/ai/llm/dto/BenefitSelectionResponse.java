package com.itplace.userapi.ai.llm.dto;

import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class BenefitSelectionResponse {
    private List<Long> benefitIds;
}
