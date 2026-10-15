package com.itplace.userapi.partner.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PartnerImagePolicyTest {

    @Test
    void keepsExistingCanonicalImageWhenCarrierCandidateDiffers() {
        String existing = "https://brand-assets.r2.dev/partners/gs25/logo.webp";
        String carrierCandidate = "https://membership.example.com/random-banner.png";

        String resolved = PartnerImagePolicy.resolveCanonicalImage(existing, carrierCandidate);

        assertThat(resolved).isEqualTo(existing);
    }

    @Test
    void preservesExistingNonCanonicalImageWhenCandidateIsNotCanonical() {
        String resolved = PartnerImagePolicy.resolveCanonicalImage(
                "https://membership.example.com/stale-working-logo.png",
                "https://carrier.example.com/new-raw-logo.png"
        );

        assertThat(resolved).isEqualTo("https://membership.example.com/stale-working-logo.png");
    }

    @Test
    void acceptsOnlyCanonicalStorageCandidateForNewPartner() {
        assertThat(PartnerImagePolicy.resolveCanonicalImage(null, "https://foo.r2.dev/partners/cgv/logo.webp"))
                .isEqualTo("https://foo.r2.dev/partners/cgv/logo.webp");
        assertThat(PartnerImagePolicy.resolveCanonicalImage(null, "https://images.itplace.click/img/cgv/logo.webp"))
                .isEqualTo("https://images.itplace.click/img/cgv/logo.webp");
        assertThat(PartnerImagePolicy.resolveCanonicalImage(
                null,
                "https://account-id.r2.cloudflarestorage.com/bucket/cgv.png"
        )).isNull();
        assertThat(PartnerImagePolicy.resolveCanonicalImage(null, "https://carrier.example.com/cgv.png"))
                .isNull();
        assertThat(PartnerImagePolicy.isCanonicalStorageUrl(
                "https://itplacepartners.s3.ap-northeast-2.amazonaws.com/img/cgv.png"
        )).isFalse();
        assertThat(PartnerImagePolicy.resolveCanonicalImage(
                null,
                "https://itplacepartners.s3.ap-northeast-2.amazonaws.com/img/cgv.png"
        )).isNull();
    }

    @Test
    void replacesExistingS3ImageWithValidR2Candidate() {
        String candidate = "https://brand-assets.r2.dev/partners/gs25/logo.webp";

        assertThat(PartnerImagePolicy.resolveCanonicalImage(
                "https://itplacepartners.s3.ap-northeast-2.amazonaws.com/img/gs25.png",
                candidate
        )).isEqualTo(candidate);
    }

    @Test
    void malformedCanonicalCandidateIsRejectedAndExistingValueIsPreserved() {
        String existing = "https://membership.example.com/working-logo.png";

        assertThat(PartnerImagePolicy.isCanonicalStorageUrl("https://foo.r2.dev/bad uri.png")).isFalse();
        assertThat(PartnerImagePolicy.resolveCanonicalImage(existing, "https://foo.r2.dev/bad uri.png"))
                .isEqualTo(existing);
    }
}
