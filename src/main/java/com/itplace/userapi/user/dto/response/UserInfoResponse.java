package com.itplace.userapi.user.dto.response;

import com.itplace.userapi.benefit.entity.enums.Grade;
import com.itplace.userapi.user.entity.Gender;
import java.time.LocalDate;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Builder
public class UserInfoResponse {
    private Long id;
    private String name;
    private String email;
    private String phoneNumber;
    private Gender gender;
    private LocalDate birthday;
    private String membershipId;
    private Grade membershipGrade;
}
