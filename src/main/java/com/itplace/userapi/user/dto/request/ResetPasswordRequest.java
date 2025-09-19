package com.itplace.userapi.user.dto.request;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ResetPasswordRequest {
    String resetPasswordToken;

    String email;

    String newPassword;

    String newPasswordConfirm;
}
