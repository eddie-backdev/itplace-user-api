package com.itplace.userapi.ai.forbiddenword.controller;

import com.itplace.userapi.ai.forbiddenword.ForbiddenWordCode;
import com.itplace.userapi.ai.forbiddenword.service.ForbiddenWordService;
import com.itplace.userapi.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/filter")
@RequiredArgsConstructor
public class ForbiddenWordController {

    private final ForbiddenWordService forbiddenWordService;

    @PostMapping
    public ResponseEntity<ApiResponse<?>> filter(@RequestBody String text) {
        String result = forbiddenWordService.censor(text);
        ApiResponse<?> body = ApiResponse.of(ForbiddenWordCode.FORBIDDEN_WORD_SUCCESS, result);
        return ResponseEntity.status(body.getStatus()).body(body);
    }
}
