package com.itplace.userapi.event.service;

import com.itplace.userapi.event.dto.response.ScratchResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ScratchServiceImpl implements ScratchService {

    @Transactional
    public ScratchResult scratch(Long userId) {
        return new ScratchResult(false, "이벤트가 종료되었습니다.", null);
    }
}
