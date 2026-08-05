package com.team7.mobile.api.controller;

import com.team7.mobile.common.dto.TripDTO;
import com.team7.mobile.common.dto.TripRequest;
import com.team7.mobile.business.service.TripService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/trips")
public class TripController {

    private final TripService tripService;

    public TripController(TripService tripService) {
        this.tripService = tripService;
    }

    /** Create a new trip */
    @PostMapping
    public ResponseEntity<TripDTO> createTrip(@Valid @RequestBody TripRequest request) {
        return ResponseEntity.ok(tripService.createTrip(request));
    }

    /** List current user's trips */
    @GetMapping
    public ResponseEntity<List<TripDTO>> getUserTrips() {
        return ResponseEntity.ok(tripService.getUserTrips());
    }

    /** Get trip detail */
    @GetMapping("/{id}")
    public ResponseEntity<TripDTO> getTrip(@PathVariable Long id) {
        return ResponseEntity.ok(tripService.getTripById(id));
    }

    /** Update trip */
    @PutMapping("/{id}")
    public ResponseEntity<TripDTO> updateTrip(@PathVariable Long id,
                                              @Valid @RequestBody TripRequest request) {
        return ResponseEntity.ok(tripService.updateTrip(id, request));
    }

    /** Cancel trip */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, String>> cancelTrip(@PathVariable Long id) {
        tripService.cancelTrip(id);
        return ResponseEntity.ok(Map.of("message", "Trip cancelled"));
    }
}
