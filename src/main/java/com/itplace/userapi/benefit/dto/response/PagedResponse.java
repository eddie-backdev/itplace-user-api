package com.itplace.userapi.benefit.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class PagedResponse<T> {
    private List<T> content;
    private int currentPage;
    private int totalPages;
    private long totalElements;
    private boolean hasNext;
}

