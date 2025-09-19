package com.itplace.userapi.event.dto;

import com.itplace.userapi.event.entity.Gift;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ScratchResult {
    private boolean success;
    private String message;
    private Gift gift;
}
