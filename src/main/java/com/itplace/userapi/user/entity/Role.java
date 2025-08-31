package com.itplace.userapi.user.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum Role {
    GUEST("ROLE_GUEST"),
    USER("ROLE_USER"),
    ADMIN("ROLE_ADMIN");

    private final String key;

    public static Role fromKey(String key) {
        for (Role role : Role.values()) {
            if (role.getKey().equals(key)) {
                return role;
            }
        }
        throw new IllegalArgumentException("No enum constant for key " + key);
    }
}
