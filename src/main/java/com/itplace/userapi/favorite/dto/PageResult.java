package com.itplace.userapi.favorite.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import org.springframework.data.domain.Page;

@Getter
@Builder
@AllArgsConstructor
public class PageResult<T> {
    private List<T> content;
    private long totalElements;
    private int totalPages;
    private int currentPage;
    private boolean hasNext;

    public static <T> PageResult<T> of(Page<T> page) {
        return PageResult.<T>builder()
                .content(page.getContent())
                .totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .currentPage(page.getNumber())
                .hasNext(page.hasNext())
                .build();
    }
}

