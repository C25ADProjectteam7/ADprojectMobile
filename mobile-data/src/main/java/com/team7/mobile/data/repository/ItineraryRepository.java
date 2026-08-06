package com.team7.mobile.data.repository;

import com.team7.mobile.data.entity.Itinerary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface ItineraryRepository extends JpaRepository<Itinerary, Long> {

    List<Itinerary> findByTripIdOrderByDayNumber(Long tripId);

    void deleteByTripId(Long tripId);
}
