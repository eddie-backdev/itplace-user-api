package com.itplace.userapi.ai.rag.document;

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
public class StoreDocument {
    private Long storeId;
    private String storeName;
    private String business;
    private String partnerName;
    private String category;
    private String city;
    private String town;
}
