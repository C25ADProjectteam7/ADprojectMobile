package com.team7.mobile.data.repository;

import com.team7.mobile.data.entity.Trip;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface TripRepository extends JpaRepository<Trip, Long> {

    List<Trip> findByUserId(Long userId);

    List<Trip> findByUserIdOrderByCreatedAtDesc(Long userId);
}
