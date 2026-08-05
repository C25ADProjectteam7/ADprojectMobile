package com.team7.mobile.business.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team7.mobile.common.exception.ExternalApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bridges Spring Boot with the Python ML service.
 * Calls the hotel price prediction endpoint implemented by the ML teammate
 * on feature/ml-hotel-price-api:
 *   POST /api/ml/predict-hotel-price
 * <p>
 * Used when the mobile app needs to display a price prediction to the user
 * (e.g. "this hotel is expected to cost X/night"). The Agent itself calls
 * the predictor directly in Python — it does not go through this client.
 */
@Service
public class MlClient {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String mlBaseUrl;

    public MlClient(
            @Value("${app.agent.ml-service.url}") String mlBaseUrl,
            RestTemplate restTemplate,
            ObjectMapper objectMapper) {
        this.mlBaseUrl = mlBaseUrl;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Predict hotel price per night / total for a stay.
     * Request fields (per ml/schemas.py):
     *   city, checkInDate (YYYY-MM-DD), checkOutDate, bookingDate,
     *   hotelStarRating (1-5), roomType (single/double/twin/suite),
     *   numberOfGuests, currency (USD only in mock stage)
     * Response fields:
     *   predictedPricePerNight, predictedTotalPrice, numberOfNights,
     *   currency, modelStatus, modelVersion, isMock, message
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> predictHotelPrice(Map<String, Object> request) {
        // Frontend sends camelCase; Python Pydantic contract expects snake_case.
        // Convert before forwarding so the ML schema validation passes.
        Map<String, Object> snakeCase = new LinkedHashMap<>();
        request.forEach((k, v) -> snakeCase.put(toSnakeCase(k), v));

        String url = mlBaseUrl + "/api/ml/predict-hotel-price";
        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(url, jsonHeaders(snakeCase), Map.class);
            return (Map<String, Object>) response.getBody();
        } catch (RestClientException e) {
            throw new ExternalApiException("MLService", url + " — " + e.getMessage(), e);
        }
    }

    /** camelCase → snake_case: checkInDate → check_in_date */
    private String toSnakeCase(String camel) {
        StringBuilder sb = new StringBuilder();
        for (char c : camel.toCharArray()) {
            if (Character.isUpperCase(c)) {
                sb.append('_').append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private HttpEntity<String> jsonHeaders(Object body) {
        try {
            String json = objectMapper.writeValueAsString(body);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            return new HttpEntity<>(json, headers);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize request body", e);
        }
    }
}
