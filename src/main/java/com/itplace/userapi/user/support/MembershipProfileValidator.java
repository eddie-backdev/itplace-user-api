package com.itplace.userapi.user.support;

import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.entity.enums.Grade;
import com.itplace.userapi.reference.ReferenceDataService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MembershipProfileValidator {
    private final ReferenceDataService referenceData;

    public boolean isValid(Carrier carrier, Grade grade) {
        return referenceData.isSelectableProfile(carrier, grade);
    }

    public boolean isValidForSignUp(Carrier carrier, Grade grade) {
        if (carrier == null || carrier == Carrier.MVNO) return grade == null;
        return isValid(carrier, grade);
    }
}
