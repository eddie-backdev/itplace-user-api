package com.itplace.userapi.favorite.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

class FavoriteRepositoryQueryContractTest {

    @Test
    void favoritePageQueriesFetchBenefitAndPartnerWithoutPerRowLazyLoads() {
        assertFetchesBenefitAndPartner("findPageByUserWithBenefitAndPartner");
        assertFetchesBenefitAndPartner("findPageByUserAndCategoryWithBenefitAndPartner");
    }

    @Test
    void categorySearchFetchesBenefitAndPartnerWithoutPerRowLazyLoads() {
        assertFetchesBenefitAndPartner("searchByUserKeywordAndPartnerCategoryWithBenefitAndPartner");
    }

    private void assertFetchesBenefitAndPartner(String methodName) {
        Query query = repositoryMethod(methodName).getAnnotation(Query.class);

        assertThat(query).isNotNull();
        assertThat(query.value())
                .containsIgnoringCase("JOIN FETCH f.benefit")
                .containsIgnoringCase("JOIN FETCH b.partner");
    }

    private Method repositoryMethod(String methodName) {
        return Arrays.stream(FavoriteRepository.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst()
                .orElseThrow();
    }
}
