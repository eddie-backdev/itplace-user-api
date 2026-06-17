package com.itplace.userapi.user.entity;

import com.itplace.userapi.common.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Table(name = "authCredential")
public class AuthCredential extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "authCredentialId")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "userId", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 30)
    private AuthCredentialType type;

    @Column(name = "provider", length = 30)
    private String provider;

    @Column(name = "providerUserId")
    private String providerUserId;

    @Column(name = "passwordHash")
    private String passwordHash;

    public static AuthCredential localPassword(User user, String passwordHash) {
        return AuthCredential.builder()
                .user(user)
                .type(AuthCredentialType.LOCAL_PASSWORD)
                .passwordHash(passwordHash)
                .build();
    }

    public static AuthCredential oauth(User user, String provider, String providerUserId) {
        return AuthCredential.builder()
                .user(user)
                .type(AuthCredentialType.OAUTH)
                .provider(provider)
                .providerUserId(providerUserId)
                .build();
    }
}
