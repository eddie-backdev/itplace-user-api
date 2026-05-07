package com.itplace.userapi.ai.llm.dto.response;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;

@Data
public class CategoryResponse {
    @JsonAlias("categories")
    private String category;
}
