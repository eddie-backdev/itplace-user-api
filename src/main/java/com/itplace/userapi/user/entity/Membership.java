package com.itplace.userapi.user.entity;

import com.itplace.userapi.benefit.entity.enums.Grade;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "membership")
public class Membership {
    @Id
    @Column(name = "membershipId", length = 16)
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private String membershipId;

    @Column(name = "grade")
    @Enumerated(EnumType.STRING)
    private Grade grade;
}
