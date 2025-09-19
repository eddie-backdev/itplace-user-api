package com.itplace.userapi.security.auth.local.dto.response;

import com.itplace.userapi.user.entity.Gender;
import com.itplace.userapi.user.entity.UplusData;
import java.time.LocalDate;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class UplusDataResponse {
    private String name;
    private String phoneNumber;
    private Gender gender;
    private LocalDate birthday;
    private String membershipId;

    public static UplusDataResponse from(UplusData uplusData) {
        return UplusDataResponse.builder()
                .name(uplusData.getName())
                .phoneNumber(uplusData.getPhoneNumber())
                .gender(uplusData.getGender())
                .birthday(uplusData.getBirthday())
                .membershipId(uplusData.getMembershipId())
                .build();
    }
}
