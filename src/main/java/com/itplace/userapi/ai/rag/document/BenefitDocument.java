package com.itplace.userapi.ai.rag.document;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class BenefitDocument {
    private String id;
    private List<Float> embedding;
    private String partnerId;
    private String partnerName;
    private String benefitId;
    private String benefitName;
    private String category;
    private String description;
}
