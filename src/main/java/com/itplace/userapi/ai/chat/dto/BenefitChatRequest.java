package com.itplace.userapi.ai.chat.dto;

import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.entity.enums.Grade;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public record BenefitChatRequest(
        @NotBlank @Size(max = 500) String question,
        @Size(max = 12) List<@NotNull @Valid ChatMessage> messages,
        @Size(max = 8) List<@NotNull @Pattern(regexp = "benefit:[A-Za-z0-9_-]+:policy:[A-Za-z0-9_-]+:(?:tier:(?:[A-Za-z0-9_-]+|policy)|offer:[A-Za-z0-9_-]+)", message = "후보 키 형식이 올바르지 않습니다") @Size(max = 150) String> previousCandidateKeys,
        @DecimalMin("-90.0") @DecimalMax("90.0") Double lat,
        @DecimalMin("-180.0") @DecimalMax("180.0") Double lng,
        Carrier carrier,
        Grade grade) {

    public BenefitChatRequest {
        messages = messages == null ? List.of() : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(messages));
        previousCandidateKeys = previousCandidateKeys == null ? List.of() : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(previousCandidateKeys));
    }

    @AssertTrue(message = "위도와 경도는 함께 입력해야 합니다")
    public boolean isCoordinatePairValid() {
        return (lat == null) == (lng == null)
                && (lat == null || Double.isFinite(lat) && Double.isFinite(lng));
    }

    public record ChatMessage(
            @NotBlank @Pattern(regexp = "user|assistant") String role,
            @NotBlank @Size(max = 3000) String content) {
    }
}
