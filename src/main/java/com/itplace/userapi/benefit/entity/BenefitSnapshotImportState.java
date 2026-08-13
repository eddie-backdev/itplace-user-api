package com.itplace.userapi.benefit.entity;

import com.itplace.userapi.benefit.entity.enums.Carrier;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "benefitSnapshotImportState")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BenefitSnapshotImportState {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(length = 16, nullable = false)
    private Carrier carrier;

    private LocalDateTime lastAppliedCrawledAt;

    public BenefitSnapshotImportState(Carrier carrier, LocalDateTime lastAppliedCrawledAt) {
        this.carrier = carrier;
        this.lastAppliedCrawledAt = lastAppliedCrawledAt;
    }

    public void markApplied(LocalDateTime crawledAt) {
        this.lastAppliedCrawledAt = crawledAt;
    }
}
