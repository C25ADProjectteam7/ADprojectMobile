package com.team7.mobile.business.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Spring is the app's only route to the ML service, and for the V3 fair-price
 * call it is a VERBATIM passthrough - unlike predictHotelPrice/getPriceAdvice,
 * which convert camelCase to snake_case.
 *
 * That distinction matters: the ML service's by-hotel-id schema is camelCase,
 * so converting here would rename candidateHotels into candidate_hotels and the
 * candidate context would be silently dropped by Pydantic.
 */
class MlClientFairPriceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private String capturedUrl;
    /** The exact JSON that goes on the wire to the ML service. */
    private String capturedJson;

    @SuppressWarnings("unchecked")
    private Map<String, Object> forward(Map<String, Object> request) {
        RestTemplate restTemplate = mock(RestTemplate.class);
        MlClient client = new MlClient("http://ml:8000", restTemplate, objectMapper);

        Map<String, Object> reply = Map.of("predictionAvailable", true, "priceLevel", "FAIR");
        when(restTemplate.postForEntity(any(String.class), any(HttpEntity.class), eq(Map.class)))
                .thenAnswer(invocation -> {
                    capturedUrl = invocation.getArgument(0);
                    capturedJson = (String) ((HttpEntity<Object>) invocation.getArgument(1)).getBody();
                    return ResponseEntity.ok(reply);
                });
        return client.predictHotelPriceV2(request);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> capturedBody() throws Exception {
        return objectMapper.readValue(capturedJson, Map.class);
    }

    private Map<String, Object> fairPriceRequest() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("hotelId", "lpB");
        request.put("hotelName", "Hotel B");
        request.put("bookingDate", "2026-08-17");
        request.put("checkInDate", "2026-08-22");
        request.put("candidateHotels", List.of(
                Map.of("hotelId", "lpA", "hotelName", "Hotel A"),
                Map.of("hotelId", "lpC", "hotelName", "Hotel C")));
        return request;
    }

    @Test
    @SuppressWarnings("unchecked")
    void forwardsCandidateHotelsVerbatimToTheByHotelIdEndpoint() throws Exception {
        forward(fairPriceRequest());

        assertEquals("http://ml:8000/api/ml/v2/hotel-price/by-hotel-id", capturedUrl);

        Map<String, Object> body = capturedBody();
        // camelCase preserved - NOT snake_cased like the other two ML calls.
        assertTrue(body.containsKey("candidateHotels"));
        assertFalse(body.containsKey("candidate_hotels"));
        assertTrue(body.containsKey("checkInDate"));
        assertFalse(body.containsKey("check_in_date"));

        List<Map<String, Object>> candidates = (List<Map<String, Object>>) body.get("candidateHotels");
        assertEquals(2, candidates.size());
        assertEquals("lpA", candidates.get(0).get("hotelId"));
        assertEquals("Hotel A", candidates.get(0).get("hotelName"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void candidatesCarryNoPriceFields() throws Exception {
        forward(fairPriceRequest());

        List<Map<String, Object>> candidates =
                (List<Map<String, Object>>) capturedBody().get("candidateHotels");
        for (Map<String, Object> candidate : candidates) {
            assertEquals(java.util.Set.of("hotelId", "hotelName"), candidate.keySet());
        }
        for (String banned : List.of("stayTotalPrice", "averagePricePerNight",
                                     "pricePerNight", "offerId", "rank", "budget")) {
            assertFalse(capturedJson.contains(banned), "wire payload must not carry " + banned);
        }
    }

    @Test
    void aRequestWithoutCandidatesIsStillForwarded() throws Exception {
        Map<String, Object> request = fairPriceRequest();
        request.remove("candidateHotels");

        Map<String, Object> response = forward(request);

        Map<String, Object> body = capturedBody();
        assertFalse(body.containsKey("candidateHotels"));
        assertEquals("lpB", body.get("hotelId"));
        assertEquals(true, response.get("predictionAvailable"));
    }
}
