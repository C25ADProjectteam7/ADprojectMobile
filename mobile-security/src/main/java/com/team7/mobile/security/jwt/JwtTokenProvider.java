package com.team7.mobile.security.jwt;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * JWT Token utility — create, parse, and validate tokens.
 * <p>
 * Supports MULTIPLE verification keys so tokens issued by the Web group
 * (different JWT_SECRET) can be validated here for cross-system approval calls:
 * - app.jwt.secret          : our primary key (used for signing new tokens)
 * - app.jwt.trusted-secrets : comma-separated extra keys accepted for verification
 * <p>
 * Shared JWT contract with the Web group:
 * - role claim has NO "ROLE_" prefix (each side adds it when building authorities)
 * - subject is the user's login name (username on mobile, email on web)
 */
@Component
public class JwtTokenProvider {

    private final SecretKey primaryKey;
    private final List<SecretKey> verificationKeys;
    private final long expirationMs;

    public JwtTokenProvider(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.expiration-ms}") long expirationMs,
            @Value("${app.jwt.trusted-secrets:}") String trustedSecrets) {
        this.primaryKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMs = expirationMs;

        this.verificationKeys = new ArrayList<>();
        this.verificationKeys.add(primaryKey);
        if (trustedSecrets != null && !trustedSecrets.isBlank()) {
            for (String s : trustedSecrets.split(",")) {
                if (!s.isBlank()) {
                    verificationKeys.add(
                            Keys.hmacShaKeyFor(s.trim().getBytes(StandardCharsets.UTF_8)));
                }
            }
        }
    }

    /**
     * Generate a JWT token signed with our primary key.
     * Role claim is stored WITHOUT the "ROLE_" prefix (shared contract).
     */
    public String generateToken(String username, String role) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirationMs);

        return Jwts.builder()
                .subject(username)
                .claim("role", role)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(primaryKey)
                .compact();
    }

    /**
     * Extract subject (username on mobile / email on web) from token.
     */
    public String getUsernameFromToken(String token) {
        return parseClaims(token).getSubject();
    }

    /**
     * Extract role claim from token (may or may not have "ROLE_" prefix
     * depending on the issuing side — normalize in JwtAuthFilter).
     */
    public String getRoleFromToken(String token) {
        return parseClaims(token).get("role", String.class);
    }

    /**
     * Validate token against ALL trusted keys (ours + Web group's).
     * @return true if the token passes signature + expiry checks with any key
     */
    public boolean validateToken(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    /** Try each trusted key until one verifies the signature. */
    private Claims parseClaims(String token) {
        JwtException last = null;
        for (SecretKey key : verificationKeys) {
            try {
                return Jwts.parser()
                        .verifyWith(key)
                        .build()
                        .parseSignedClaims(token)
                        .getPayload();
            } catch (JwtException | IllegalArgumentException e) {
                last = e instanceof JwtException ? (JwtException) e
                        : new JwtException("Illegal argument", e);
            }
        }
        throw last != null ? last : new JwtException("No verification key matched");
    }
}
