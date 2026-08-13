package com.itplace.userapi.benefit.repository;

import com.itplace.userapi.benefit.entity.BenefitSnapshotImportState;
import com.itplace.userapi.benefit.entity.enums.Carrier;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BenefitSnapshotImportStateRepository
        extends JpaRepository<BenefitSnapshotImportState, Carrier> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT state FROM BenefitSnapshotImportState state WHERE state.carrier = :carrier")
    Optional<BenefitSnapshotImportState> findByCarrierForUpdate(@Param("carrier") Carrier carrier);
}
