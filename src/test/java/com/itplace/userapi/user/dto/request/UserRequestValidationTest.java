package com.itplace.userapi.user.dto.request;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class UserRequestValidationTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    @Test
    void resetPasswordRejectsMissingRequiredFields() {
        ResetPasswordRequest request = new ResetPasswordRequest();

        assertThat(validator.validate(request))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("resetPasswordToken", "email", "newPassword", "newPasswordConfirm");
    }

    @Test
    void resetPasswordUsesSharedPasswordPolicy() {
        ResetPasswordRequest request = validResetPasswordRequest();
        request.setNewPassword("abcdef");
        request.setNewPasswordConfirm("abcdef");

        assertThat(validator.validate(request))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("newPassword", "newPasswordConfirm");
    }

    @Test
    void changePasswordUsesSharedPasswordPolicy() {
        ChangePasswordRequest request = new ChangePasswordRequest();
        request.setOldPassword("old-password!");
        request.setNewPassword("abcdef");
        request.setNewPasswordConfirm("abcdef");

        assertThat(validator.validate(request))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("newPassword", "newPasswordConfirm");
    }

    @Test
    void withdrawAllowsBlankPasswordForSocialAccountFlow() {
        WithdrawRequest request = new WithdrawRequest();
        request.setPassword(" ");

        assertThat(validator.validate(request)).isEmpty();
    }

    private ResetPasswordRequest validResetPasswordRequest() {
        ResetPasswordRequest request = new ResetPasswordRequest();
        request.setResetPasswordToken("reset-token");
        request.setEmail("user@example.com");
        request.setNewPassword("abcdef!");
        request.setNewPasswordConfirm("abcdef!");
        return request;
    }
}
