package com.itplace.userapi.user.support;

public final class PasswordPolicy {

    public static final int MIN_LENGTH = 6;
    public static final int MAX_LENGTH = 30;
    public static final String LENGTH_MESSAGE = "비밀번호는 6자 이상 30자 이하로 입력해주세요.";
    public static final String SPECIAL_CHARACTER_PATTERN = "^(?=.*[!@#$%^&*()_+{}\\[\\]:;<>,.?~/-]).*$";
    public static final String SPECIAL_CHARACTER_MESSAGE = "비밀번호에는 특수문자를 최소 1개 이상 포함해야 합니다.";

    private PasswordPolicy() {
    }
}
