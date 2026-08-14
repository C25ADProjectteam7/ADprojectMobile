package com.team7.mobile.data.repository;

import com.team7.mobile.data.entity.Expense;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface ExpenseRepository extends JpaRepository<Expense, Long> {

    // ExpenseService.toDTO reads trip (title/destination) and user (id) outside
    // any open session - without the fetch join those lazy proxies throw
    // LazyInitializationException (observed live on GET /api/expenses).
    @EntityGraph(attributePaths = {"trip", "user"})
    List<Expense> findByUserId(Long userId);

    @EntityGraph(attributePaths = {"trip", "user"})
    List<Expense> findByTripId(Long tripId);

    @EntityGraph(attributePaths = {"trip", "user"})
    Optional<Expense> findByIdAndUserId(Long id, Long userId);

    @Override
    @EntityGraph(attributePaths = {"trip", "user"})
    List<Expense> findAll();

    @Override
    @EntityGraph(attributePaths = {"trip", "user"})
    Optional<Expense> findById(Long id);
}
