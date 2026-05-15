package com.itplace.userapi.user.service;

import com.itplace.userapi.security.auth.common.PrincipalDetails;
import com.itplace.userapi.security.verification.email.dto.request.EmailConfirmRequest;
import com.itplace.userapi.user.dto.request.ChangePasswordRequest;
import com.itplace.userapi.user.dto.request.MembershipProfileUpdateRequest;
import com.itplace.userapi.user.dto.request.ResetPasswordRequest;
import com.itplace.userapi.user.dto.response.FindPasswordConfirmResponse;
import com.itplace.userapi.user.dto.response.UserInfoResponse;

public interface UserService {
    UserInfoResponse getUserInfo(Long userId);

    FindPasswordConfirmResponse findPasswordConfirm(EmailConfirmRequest request);

    void resetPassword(ResetPasswordRequest request);

    void changePassword(PrincipalDetails principalDetails, ChangePasswordRequest request);

    UserInfoResponse updateMembershipProfile(PrincipalDetails principalDetails, MembershipProfileUpdateRequest request);

    void withdraw(Long userId, String password);

}
