package com.itplace.userapi.event.service;


import com.itplace.userapi.event.dto.response.ScratchResult;

public interface ScratchService {
    ScratchResult scratch(Long userId);
}
