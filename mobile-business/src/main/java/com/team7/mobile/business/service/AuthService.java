package com.team7.mobile.business.service;

import com.team7.mobile.common.dto.LoginRequest;
import com.team7.mobile.common.dto.LoginResponse;
import com.team7.mobile.common.dto.RegisterRequest;
import com.team7.mobile.data.entity.User;
import com.team7.mobile.data.repository.UserRepository;
import com.team7.mobile.security.jwt.JwtTokenProvider;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider jwtTokenProvider;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AuthService(AuthenticationManager authenticationManager,
                       JwtTokenProvider jwtTokenProvider,
                       UserRepository userRepository,
                       PasswordEncoder passwordEncoder) {
        this.authenticationManager = authenticationManager;
        this.jwtTokenProvider = jwtTokenProvider;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Authenticate user and return JWT token.
     */
    public LoginResponse login(LoginRequest request) {
        Authentication auth = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword())
        );
        User user = userRepository.findByUsername(request.getUsername())
                .orElseThrow(() -> new RuntimeException("User not found"));
        // Role claim WITHOUT the "ROLE_" prefix — shared JWT contract with the Web group.
        // Each side's filter adds the prefix when building Spring Security authorities.
        String role = user.getRole().name();
        String token = jwtTokenProvider.generateToken(request.getUsername(), role);
        return new LoginResponse(token, "Bearer", 86400000L,
                user.getId(), user.getUsername(), role);
    }

    /**
     * Register a new employee account.
     */
    public void register(RegisterRequest request) {
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new RuntimeException("Username already exists");
        }
        User user = new User();
        user.setUsername(request.getUsername());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setEmail(request.getEmail());
        user.setDepartment(request.getDepartment());
        user.setPhone(request.getPhone());
        userRepository.save(user);
    }
}
