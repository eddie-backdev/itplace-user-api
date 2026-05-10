package com.itplace.userapi.inquiry.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class InquiryCreateRequest {

    @NotBlank(message = "문의 유형을 선택해 주세요.")
    @Size(max = 50, message = "문의 유형은 50자 이하여야 합니다.")
    private String category;

    @NotBlank(message = "제목을 입력해 주세요.")
    @Size(max = 200, message = "제목은 200자 이하여야 합니다.")
    private String title;

    @NotBlank(message = "문의 내용을 입력해 주세요.")
    @Size(max = 4000, message = "문의 내용은 4000자 이하여야 합니다.")
    private String content;

}
