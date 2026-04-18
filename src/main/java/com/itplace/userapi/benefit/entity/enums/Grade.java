package com.itplace.userapi.benefit.entity.enums;

import lombok.Getter;

@Getter
public enum Grade {
    // LGU+ 등급
    BASIC,
    VIP,
    VVIP,
    VIP콕,

    // SKT T멤버십 등급 (SILVER → GOLD → VIP)
    SKT_SILVER,
    SKT_GOLD,
    SKT_VIP,

    // KT 멤버십 등급 (GENERAL → WHITE → SILVER → GOLD → VIP → VVIP)
    KT_GENERAL,
    KT_WHITE,
    KT_SILVER,
    KT_GOLD,
    KT_VIP,
    KT_VVIP
}
