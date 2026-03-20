package com.itplace.userapi.ai.question.dto.request;

import lombok.Data;

@Data
public class QuestionSaveRequest {
    private String question;
    private String category;
}

