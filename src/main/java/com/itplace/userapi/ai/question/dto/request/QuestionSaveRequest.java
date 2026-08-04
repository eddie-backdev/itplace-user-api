package com.itplace.userapi.ai.question.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class QuestionSaveRequest {

    @NotBlank(message = "질문을 입력해 주세요.")
    @Size(max = 200, message = "질문은 200자 이하여야 합니다.")
    private String question;

    @NotBlank(message = "카테고리를 입력해 주세요.")
    @Size(max = 100, message = "카테고리는 100자 이하여야 합니다.")
    private String category;
}
