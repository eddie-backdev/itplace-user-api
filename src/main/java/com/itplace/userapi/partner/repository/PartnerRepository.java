package com.itplace.userapi.partner.repository;

import com.itplace.userapi.partner.entity.Partner;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PartnerRepository extends JpaRepository<Partner, Long> {

    Optional<Partner> findByPartnerId(Long partnerId);

    Optional<Partner> findByPartnerName(String partnerName);

    List<Partner> findAllByPartnerName(String partnerName);

    List<Partner> findAllByPartnerNameIn(List<String> partnerNames);
}
