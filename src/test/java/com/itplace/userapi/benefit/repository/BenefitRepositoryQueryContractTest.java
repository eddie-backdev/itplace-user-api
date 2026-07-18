package com.itplace.userapi.benefit.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

class BenefitRepositoryQueryContractTest {

    @Test
    void policyBatchQueryFetchesBenefitAndBenefitPolicy() {
        Query query = repositoryMethod(BenefitCarrierPolicyRepository.class, "findAllByBenefitIn")
                .getAnnotation(Query.class);

        assertThat(query).isNotNull();
        assertThat(query.value())
                .containsIgnoringCase("JOIN FETCH p.benefit")
                .containsIgnoringCase("JOIN FETCH p.benefitPolicy");
    }

    private Method repositoryMethod(Class<?> repositoryType, String methodName) {
        return Arrays.stream(repositoryType.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst()
                .orElseThrow();
    }
}
