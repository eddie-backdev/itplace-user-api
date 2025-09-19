package com.itplace.userapi.user.service;

import com.itplace.userapi.security.auth.common.PrincipalDetails;
import com.itplace.userapi.security.verification.email.dto.EmailConfirmRequest;
import com.itplace.userapi.user.dto.request.ChangePasswordRequest;
import com.itplace.userapi.user.dto.request.FindEmailConfirmRequest;
import com.itplace.userapi.user.dto.request.ResetPasswordRequest;
import com.itplace.userapi.user.dto.response.CheckUplusDataResponse;
import com.itplace.userapi.user.dto.response.FindEmailResponse;
import com.itplace.userapi.user.dto.response.FindPasswordConfirmResponse;
import com.itplace.userapi.user.dto.response.UserInfoResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

public interface UserService {
    UserInfoResponse getUserInfo(Long userId);

    FindEmailResponse findEmailConfirm(FindEmailConfirmRequest request);

    FindPasswordConfirmResponse findPasswordConfirm(EmailConfirmRequest request);

    void resetPassword(ResetPasswordRequest request);

    void changePassword(PrincipalDetails principalDetails, ChangePasswordRequest request);

    void withdraw(Long userId, String password);

    CheckUplusDataResponse checkUplusData(@AuthenticationPrincipal PrincipalDetails principalDetails);

    void linkUplusData(@AuthenticationPrincipal PrincipalDetails principalDetails);

    Integer getUserCouponCount(Long userId);
}