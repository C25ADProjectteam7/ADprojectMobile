package com.team7.mobile.business.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.task.DelegatingSecurityContextAsyncTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Enables @Async for background tasks (Agent itinerary generation) and
 * @Scheduled for periodic maintenance (e.g. AgentChatService's expired-task cleanup).
 * <p>
 * Also wires the @Async executor through DelegatingSecurityContextAsyncTaskExecutor:
 * Spring Security's SecurityContextHolder is thread-local by default and does
 * NOT propagate to a new thread on its own, so without this, any @Async method
 * that calls CurrentUser.get()/getId() (e.g. AgentChatService.executeAsync ->
 * TripService.agentChat) would see no authenticated user at all once @Async
 * actually runs on a separate thread - confirmed by direct testing.
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig implements AsyncConfigurer {

    @Override
    public Executor getAsyncExecutor() {
        AsyncTaskExecutor delegate = new SimpleAsyncTaskExecutor();
        return new DelegatingSecurityContextAsyncTaskExecutor(delegate);
    }
}
