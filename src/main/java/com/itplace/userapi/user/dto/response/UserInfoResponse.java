package com.itplace.userapi.user.dto.response;

import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.entity.enums.Grade;
import com.itplace.userapi.user.entity.Gender;
import com.itplace.userapi.user.entity.User;
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
    private Carrier carrier;
    private Grade membershipGradeCode;
    private Boolean membershipVerified;
    private Boolean hasLocalPassword;
    private Boolean socialAccount;

    /**
     * @deprecated use membershipGradeCode. Kept temporarily for response compatibility.
     */
    @Deprecated
    private Grade membershipGrade;

    public static UserInfoResponse from(User user) {
        return UserInfoResponse.builder()
                .id(user.getId())
                .name(user.getName())
                .email(user.getEmail())
                .phoneNumber(user.getPhoneNumber())
                .gender(user.getGender())
                .birthday(user.getBirthday())
                .carrier(user.getCarrier())
                .membershipGradeCode(user.getMembershipGradeCode())
                .membershipGrade(user.getMembershipGradeCode())
                .membershipVerified(Boolean.TRUE.equals(user.getMembershipVerified()))
                .hasLocalPassword(user.getAuthCredentials().stream()
                        .anyMatch(credential -> credential.getType() == com.itplace.userapi.user.entity.AuthCredentialType.LOCAL_PASSWORD))
                .socialAccount(user.getAuthCredentials().stream()
                        .anyMatch(credential -> credential.getType() == com.itplace.userapi.user.entity.AuthCredentialType.OAUTH))
                .build();
    }
}
