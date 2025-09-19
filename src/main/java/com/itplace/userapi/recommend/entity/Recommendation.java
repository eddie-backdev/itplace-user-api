package com.itplace.userapi.recommend.entity;

import com.itplace.userapi.benefit.entity.Benefit;
import com.itplace.userapi.common.BaseTimeEntity;
import com.itplace.userapi.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "recommendations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Recommendation extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long recommendationId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "userId", nullable = false)
    private User user;

    @Column(name = "ranking")
    private int rank;

    private String partnerName;

    @Column(columnDefinition = "TEXT")
    private String reason;

    @Column(length = 512)
    private String imgUrl;

    @ManyToMany
    @JoinTable(
            name = "recommendation_benefits",
            joinColumns = @JoinColumn(name = "recommendationId"),
            inverseJoinColumns = @JoinColumn(name = "benefitId")
    )
    private List<Benefit> benefits = new ArrayList<>();
}
