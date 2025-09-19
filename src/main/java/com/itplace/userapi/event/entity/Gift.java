package com.itplace.userapi.event.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "gift")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Gift {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long giftId;

    @Column(length = 255)
    private String giftName;

    @Column(nullable = false)
    private Integer giftCount;

    private Integer total;

    @Column(length = 512)
    private String imgUrl;
}
