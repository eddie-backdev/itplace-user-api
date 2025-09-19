package com.itplace.userapi.user.entity;

import com.itplace.userapi.common.BaseTimeEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

@Getter
@Setter
@Entity
@Builder
@ToString(exclude = "socialAccounts")
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "user")
public class User extends BaseTimeEntity {

    @Id
    @Column(name = "userId")
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", length = 30)
    private String name;

    @Column(name = "email", unique = true)
    private String email;

    @Column(name = "password")
    private String password;

    @Column(name = "phoneNumber", length = 11, unique = true)
    private String phoneNumber;

    @Column(name = "gender", length = 5)
    @Enumerated(EnumType.STRING)
    private Gender gender;

    @Column(name = "birthday", length = 8)
    private LocalDate birthday;

    @Column(name = "membershipId", length = 16)
    private String membershipId;

    @Column(name = "role", nullable = false, length = 5)
    @Enumerated(EnumType.STRING)
    private Role role;

    @Column(name = "coupon")
    private int coupon;

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<SocialAccount> socialAccounts = new ArrayList<>();

    public Collection<? extends GrantedAuthority> getAuthorities() {
        return Collections.singletonList(new SimpleGrantedAuthority(this.role.getKey()));
    }

    // OAuth2를 통한 신규 유저 생성을 위한 정적 팩토리 메소드
    public static User of(String email) {
        return User.builder()
                .email(email)
                .password(UUID.randomUUID().toString())
                .role(Role.GUEST)
                .build();
    }
}
