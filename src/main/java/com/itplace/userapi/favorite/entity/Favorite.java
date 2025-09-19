package com.itplace.userapi.favorite.entity;

import com.itplace.userapi.benefit.entity.Benefit;
import com.itplace.userapi.common.BaseTimeEntity;
import com.itplace.userapi.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@IdClass(FavoriteId.class)
@Table(name = "favorite")
public class Favorite extends BaseTimeEntity {

    @Id
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "userId")
    private User user;

    @Id
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "benefitId")
    private Benefit benefit;
}
