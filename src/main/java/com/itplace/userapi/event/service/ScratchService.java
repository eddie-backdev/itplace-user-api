package com.itplace.userapi.event.service;


import com.itplace.userapi.event.dto.ScratchResult;

public interface ScratchService {
    ScratchResult scratch(Long userId);
}
