package com.team7.mobile.data.repository;

import com.team7.mobile.data.entity.ItineraryItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface ItineraryItemRepository extends JpaRepository<ItineraryItem, Long> {

    List<ItineraryItem> findByItineraryId(Long itineraryId);

    List<ItineraryItem> findByItineraryIdIn(List<Long> itineraryIds);

    void deleteByItineraryId(Long itineraryId);
}
