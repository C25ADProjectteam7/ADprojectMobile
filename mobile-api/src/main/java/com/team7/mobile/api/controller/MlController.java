package com.team7.mobile.api.controller;

import com.team7.mobile.business.agent.MlClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * ML API endpoints exposed to the mobile frontend.
 * Proxies calls to the Python ML service so the Android app never talks to Python directly.
 */
@RestController
@RequestMapping("/api/ml")
public class MlController {

    private final MlClient mlClient;

    public MlController(MlClient mlClient) {
        this.mlClient = mlClient;
    }

    /**
     * Hotel price prediction for display in the app.
     * Example body:
     *   {
     *     "city": "Tokyo",
     *     "checkInDate": "2026-08-10",
     *     "checkOutDate": "2026-08-13",
     *     "bookingDate": "2026-07-31",
     *     "hotelStarRating": 4,
     *     "roomType": "double",
     *     "numberOfGuests": 2,
     *     "currency": "USD"
     *   }
     */
    @PostMapping("/predict-hotel-price")
    public ResponseEntity<Map<String, Object>> predictHotelPrice(@RequestBody Map<String, Object> request) {
        return ResponseEntity.ok(mlClient.predictHotelPrice(request));
    }
}
