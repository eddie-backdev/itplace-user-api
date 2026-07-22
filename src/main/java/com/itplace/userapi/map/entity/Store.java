package com.itplace.userapi.map.entity;

import com.itplace.userapi.partner.entity.Partner;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.locationtech.jts.geom.Point;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "store", indexes = {
        @Index(name = "idx_store_lat_lng", columnList = "latitude, longitude")
})
public class Store {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "storeId", updatable = false, nullable = false)
    private long storeId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "partnerId", nullable = false)
    private Partner partner;

    @Column(name = "storeName")
    private String storeName;

    @Column(name = "business")
    private String business;

    @Column(name = "city", length = 50)
    private String city;

    @Column(name = "town")
    private String town;

    @Column(name = "legalDong")
    private String legalDong;

    @Column(name = "address")
    private String address;

    @Column(name = "roadName")
    private String roadName;

    @Column(name = "roadAddress")
    private String roadAddress;

    @Column(name = "postCode", length = 50)
    private String postCode;

    @Column(name = "longitude", precision = 15, scale = 12)
    private BigDecimal longitude;

    @Column(name = "latitude", precision = 15, scale = 12)
    private BigDecimal latitude;

    @Column(name = "location", columnDefinition = "geometry(Point, 4326)", nullable = false)
    private Point location;

    @Column(name = "hasCoupon")
    private boolean hasCoupon;

    @Column(name = "sourceProvider", length = 32)
    private String sourceProvider;

    @Column(name = "sourcePlaceId", length = 128)
    private String sourcePlaceId;

    @Builder.Default
    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "lastSeenAt")
    private Instant lastSeenAt;

    @Column(name = "lastSeenRunId", length = 36)
    private String lastSeenRunId;

    @Builder.Default
    @Column(name = "healthyMissCount", nullable = false)
    private int healthyMissCount = 0;

    @Column(name = "inactivatedAt")
    private Instant inactivatedAt;

}
