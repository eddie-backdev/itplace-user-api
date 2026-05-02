package com.itplace.userapi.benefit.entity;

import com.itplace.userapi.benefit.entity.enums.MainCategory;
import com.itplace.userapi.benefit.entity.enums.MainCategoryConverter;
import com.itplace.userapi.common.BaseTimeEntity;
import com.itplace.userapi.favorite.entity.Favorite;
import com.itplace.userapi.partner.entity.Partner;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Entity
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "benefit")
public class Benefit extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long benefitId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "partnerId")
    private Partner partner;

    @Convert(converter = MainCategoryConverter.class)
    private MainCategory mainCategory;

    private String benefitName;

    /** 통신사별 정책 row를 하나의 공통 혜택으로 묶기 위한 정규화 키. */
    @Column(length = 512)
    private String canonicalKey;

    @Builder.Default
    @Column(name = "active", nullable = false)
    private Boolean active = true;

    // 일단 benefit 삭제 시 외래키 제약 위반 걸어줘서 오류 처리
    // 즐겨찾기 먼저 제거 -> 혜택 삭제
    @Builder.Default
    @OneToMany(mappedBy = "benefit")
    private List<Favorite> favorites = new ArrayList<>();

    @Builder.Default
    @OneToMany(mappedBy = "benefit")
    private List<BenefitCarrierPolicy> carrierPolicies = new ArrayList<>();

}
