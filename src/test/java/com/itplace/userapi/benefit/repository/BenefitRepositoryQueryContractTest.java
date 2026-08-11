package com.itplace.userapi.benefit.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.itplace.userapi.benefit.entity.enums.UsageType;
import com.itplace.userapi.benefit.entity.enums.UsageTypeConverter;
import com.itplace.userapi.partner.repository.PartnerRepository;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

class BenefitRepositoryQueryContractTest {

    private final UsageTypeConverter usageTypeConverter = new UsageTypeConverter();

    @Test
    void policyBatchQueryFetchesBenefitAndBenefitPolicy() {
        Query query = repositoryMethod(BenefitCarrierPolicyRepository.class, "findAllByBenefitIn")
                .getAnnotation(Query.class);

        assertThat(query).isNotNull();
        assertThat(query.value())
                .containsIgnoringCase("JOIN FETCH p.benefit")
                .containsIgnoringCase("JOIN FETCH p.benefitPolicy");
    }

    @Test
    void benefitFilterQueriesMatchLowercaseUsageTypeLabelsStoredByConverter() {
        Query query = repositoryMethod(BenefitRepository.class, "findFilteredBenefits")
                .getAnnotation(Query.class);

        assertUsageTypeLabels(query.value());
        assertUsageTypeLabels(query.countQuery());
    }

    @Test
    void partnerFilterQueriesMatchLowercaseUsageTypeLabelsStoredByConverter() {
        Query query = repositoryMethod(PartnerRepository.class, "findBenefitPartners")
                .getAnnotation(Query.class);

        assertUsageTypeLabels(query.value());
        assertUsageTypeLabels(query.countQuery());
    }

    @Test
    void usageTypeConverterStoresLowercaseLabelsUsedByNativeFilters() {
        assertThat(usageTypeConverter.convertToDatabaseColumn(UsageType.ONLINE)).isEqualTo("online");
        assertThat(usageTypeConverter.convertToDatabaseColumn(UsageType.OFFLINE)).isEqualTo("offline");
        assertThat(usageTypeConverter.convertToDatabaseColumn(UsageType.BOTH)).isEqualTo("both");
    }

    private void assertUsageTypeLabels(String query) {
        assertThat(query)
                .contains(
                        ":filter = 'ONLINE' AND bcp.usageType IN ('online', 'both')",
                        ":filter = 'OFFLINE' AND bcp.usageType IN ('offline', 'both')"
                )
                .doesNotContain(
                        "bcp.usageType IN ('ONLINE', 'BOTH')",
                        "bcp.usageType IN ('OFFLINE', 'BOTH')"
                );
    }

    private Method repositoryMethod(Class<?> repositoryType, String methodName) {
        return Arrays.stream(repositoryType.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst()
                .orElseThrow();
    }
}
