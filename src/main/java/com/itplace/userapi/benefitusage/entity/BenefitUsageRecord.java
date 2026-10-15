package com.itplace.userapi.benefitusage.entity;

import com.itplace.userapi.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "benefitUsageRecord")
public class BenefitUsageRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "recordId")
    private Long recordId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "userId", nullable = false)
    private User user;

    @Column(name = "benefitId", nullable = false)
    private Long benefitId;

    @Column(name = "partnerId", nullable = false)
    private Long partnerId;

    @Column(name = "benefitName", nullable = false, length = 512)
    private String benefitName;

    @Column(name = "partnerName", nullable = false, length = 255)
    private String partnerName;

    @Column(name = "partnerImage", length = 512)
    private String partnerImage;

    @Column(name = "usedAt", nullable = false)
    private LocalDate usedAt;

    @Column(name = "savedAmountKrw")
    private Long savedAmountKrw;

    @Column(length = 200)
    private String memo;

    public BenefitUsageRecord(
            User user,
            Long benefitId,
            Long partnerId,
            String benefitName,
            String partnerName,
            String partnerImage,
            LocalDate usedAt,
            Long savedAmountKrw,
            String memo
    ) {
        this.user = user;
        this.benefitId = benefitId;
        this.partnerId = partnerId;
        this.benefitName = benefitName;
        this.partnerName = partnerName;
        this.partnerImage = partnerImage;
        this.usedAt = usedAt;
        this.savedAmountKrw = savedAmountKrw;
        this.memo = memo;
    }

    public void updateUsageDetails(LocalDate usedAt, Long savedAmountKrw, String memo) {
        this.usedAt = usedAt;
        this.savedAmountKrw = savedAmountKrw;
        this.memo = memo;
    }
}
