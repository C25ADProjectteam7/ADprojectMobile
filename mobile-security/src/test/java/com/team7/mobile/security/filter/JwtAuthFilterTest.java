package com.team7.mobile.security.filter;

import com.team7.mobile.security.jwt.JwtTokenProvider;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression test for a real bug found via code review: a signature-valid
 * JWT (passes validateToken()) whose role claim is null/blank used to crash
 * this filter with an unhandled IllegalArgumentException from
 * SimpleGrantedAuthority(null) - verified directly that constructor throws
 * on null. This matters specifically because JwtTokenProvider intentionally
 * accepts tokens signed by the Web group's system (app.jwt.trusted-secrets),
 * whose tokens aren't guaranteed to carry the same role-claim shape as ours.
 * A crash here happens inside the servlet filter chain, before
 * GlobalExceptionHandler ever gets a chance to produce a clean response.
 */
class JwtAuthFilterTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void missingRoleClaimDoesNotCrashTheFilter() throws Exception {
        JwtTokenProvider tokenProvider = mock(JwtTokenProvider.class);
        when(tokenProvider.validateToken("token-with-no-role")).thenReturn(true);
        when(tokenProvider.getUsernameFromToken("token-with-no-role")).thenReturn("web-group-user@example.com");
        when(tokenProvider.getRoleFromToken("token-with-no-role")).thenReturn(null);

        JwtAuthFilter filter = new JwtAuthFilter(tokenProvider);

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("Authorization")).thenReturn("Bearer token-with-no-role");
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        assertDoesNotThrow(() -> filter.doFilterInternal(request, response, chain));

        verify(chain).doFilter(any(), any());
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(auth);
        assertEquals("web-group-user@example.com", auth.getName());
        assertTrue(auth.getAuthorities().isEmpty());
    }

    @Test
    void normalRoleClaimStillWorks() throws Exception {
        JwtTokenProvider tokenProvider = mock(JwtTokenProvider.class);
        when(tokenProvider.validateToken("normal-token")).thenReturn(true);
        when(tokenProvider.getUsernameFromToken("normal-token")).thenReturn("agenttest01");
        when(tokenProvider.getRoleFromToken("normal-token")).thenReturn("EMPLOYEE");

        JwtAuthFilter filter = new JwtAuthFilter(tokenProvider);

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("Authorization")).thenReturn("Bearer normal-token");
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(auth);
        assertEquals(1, auth.getAuthorities().size());
        assertEquals("ROLE_EMPLOYEE", auth.getAuthorities().iterator().next().toString());
    }
}
