package com.itplace.userapi.benefit.entity;

import com.itplace.userapi.benefit.entity.enums.Grade;
import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class TierBenefitId implements Serializable {
    private Grade grade;
    private Long benefit;
}
