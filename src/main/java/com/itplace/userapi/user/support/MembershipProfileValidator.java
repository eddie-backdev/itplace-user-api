package com.itplace.userapi.user.support;

import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.entity.enums.Grade;
import java.util.Map;
import java.util.Set;

public final class MembershipProfileValidator {

    private static final Map<Carrier, Set<Grade>> VALID_GRADES = Map.of(
            Carrier.LGU, Set.of(Grade.BASIC, Grade.VIP, Grade.VVIP, Grade.VIP콕),
            Carrier.SKT, Set.of(Grade.SKT_SILVER, Grade.SKT_GOLD, Grade.SKT_VIP),
            Carrier.KT, Set.of(Grade.KT_GENERAL, Grade.KT_WHITE, Grade.KT_SILVER, Grade.KT_GOLD, Grade.KT_VIP, Grade.KT_VVIP)
    );

    private MembershipProfileValidator() {
    }

    public static boolean isValid(Carrier carrier, Grade membershipGradeCode) {
        return carrier != null
                && membershipGradeCode != null
                && VALID_GRADES.getOrDefault(carrier, Set.of()).contains(membershipGradeCode);
    }
}
