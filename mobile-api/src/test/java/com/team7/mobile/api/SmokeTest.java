package com.team7.mobile.api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Smoke test — verifies Spring context loads without errors
 */
@SpringBootTest
@ActiveProfiles("dev")
class SmokeTest {

    @Test
    void contextLoads() {
        // Passes if Spring application context starts successfully
    }
}
