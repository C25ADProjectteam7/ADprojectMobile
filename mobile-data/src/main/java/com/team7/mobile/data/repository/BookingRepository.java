package com.team7.mobile.data.repository;

import com.team7.mobile.data.entity.Booking;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface BookingRepository extends JpaRepository<Booking, Long> {

    List<Booking> findByUserId(Long userId);

    List<Booking> findByTripId(Long tripId);

    Optional<Booking> findByIdAndUserId(Long id, Long userId);
}
