package com.itplace.userapi.user.dto.request;

import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.entity.enums.Grade;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MembershipProfileUpdateRequest {
    @NotNull(message = "통신사는 필수 항목입니다.")
    private Carrier carrier;

    @NotNull(message = "멤버십 등급은 필수 항목입니다.")
    private Grade membershipGradeCode;
}
