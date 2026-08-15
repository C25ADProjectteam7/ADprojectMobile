package com.team7.mobile.data.repository;

import com.team7.mobile.data.entity.MobileApproval;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MobileApprovalRepository extends JpaRepository<MobileApproval, Long> {

    Optional<MobileApproval> findByMobileTripId(Long mobileTripId);
}
