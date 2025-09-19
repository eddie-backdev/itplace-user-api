package com.itplace.userapi.security.auth.local.dto.response;

import com.itplace.userapi.user.entity.Gender;
import java.time.LocalDate;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class LoadOAuthDataResponse {

    private String name;

    private String phoneNumber;

    private LocalDate birthday;

    private Gender gender;

    private String membershipId;
}
