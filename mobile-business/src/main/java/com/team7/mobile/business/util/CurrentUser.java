package com.team7.mobile.business.util;

import com.team7.mobile.data.entity.User;
import com.team7.mobile.data.repository.UserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Resolves the authenticated user from the SecurityContext (set by JwtAuthFilter).
 */
@Component
public class CurrentUser {

    private final UserRepository userRepository;

    public CurrentUser(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Returns the authenticated User entity, or null if not authenticated.
     */
    public User get() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return null;
        }
        String username = auth.getName();
        return userRepository.findByUsername(username).orElse(null);
    }

    /**
     * Returns the authenticated user's id.
     * @throws IllegalStateException if not authenticated
     */
    public Long getId() {
        User user = get();
        if (user == null) {
            throw new IllegalStateException("User not authenticated");
        }
        return user.getId();
    }
}
