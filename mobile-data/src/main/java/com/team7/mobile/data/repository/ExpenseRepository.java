package com.team7.mobile.data.repository;

import com.team7.mobile.data.entity.Expense;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface ExpenseRepository extends JpaRepository<Expense, Long> {

    List<Expense> findByUserId(Long userId);

    List<Expense> findByTripId(Long tripId);

    Optional<Expense> findByIdAndUserId(Long id, Long userId);
}
