package com.itplace.userapi.log.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class SearchRankResponse {
    private String partnerName;
    private long searchCount;
    private long rank;
    private long previousRank;
    private long rankChange;
    private String changeDerection;
}
