package com.team7.mobile.business.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Enables @Async for background tasks (Agent itinerary generation).
 */
@Configuration
@EnableAsync
public class AsyncConfig {
}
