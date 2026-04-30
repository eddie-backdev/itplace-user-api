package com.itplace.userapi.security.auth.local.dto.response;

import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.entity.enums.Grade;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class LoginResponse {

    String name;
    Carrier carrier;
    Grade membershipGradeCode;
    Boolean membershipVerified;

    /**
     * @deprecated use membershipGradeCode. Kept temporarily for response compatibility.
     */
    @Deprecated
    Grade membershipGrade;
}
