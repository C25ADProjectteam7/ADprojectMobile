package com.team7.mobile.business.service;

import com.team7.mobile.common.dto.UserDTO;
import com.team7.mobile.common.exception.BusinessException;
import com.team7.mobile.data.entity.User;
import com.team7.mobile.data.repository.UserRepository;
import com.team7.mobile.business.util.CurrentUser;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * User profile management — view/update own profile, change password.
 */
@Service
public class UserService {

    private final UserRepository userRepository;
    private final CurrentUser currentUser;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, CurrentUser currentUser,
                       PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.currentUser = currentUser;
        this.passwordEncoder = passwordEncoder;
    }

    /** Current user's profile. */
    public UserDTO getMe() {
        User user = requireUser();
        return toDTO(user);
    }

    /** Update own profile (email, phone, department, avatar). */
    public UserDTO updateMe(Map<String, String> fields) {
        User user = requireUser();
        if (fields.containsKey("email")) user.setEmail(fields.get("email"));
        if (fields.containsKey("phone")) user.setPhone(fields.get("phone"));
        if (fields.containsKey("department")) user.setDepartment(fields.get("department"));
        userRepository.save(user);
        return toDTO(user);
    }

    /** Change password — verifies the old password first. */
    public void changePassword(String oldPassword, String newPassword) {
        User user = requireUser();
        if (!passwordEncoder.matches(oldPassword, user.getPassword())) {
            throw new BusinessException("INVALID_OLD_PASSWORD", "Old password is incorrect", 400);
        }
        if (newPassword == null || newPassword.length() < 8) {
            throw new BusinessException("WEAK_PASSWORD", "New password must be at least 8 characters", 400);
        }
        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);
    }

    private User requireUser() {
        User user = currentUser.get();
        if (user == null) {
            throw new BusinessException("UNAUTHORIZED", "Not authenticated", 401);
        }
        return user;
    }

    private UserDTO toDTO(User user) {
        return new UserDTO(
                user.getId(), user.getUsername(), user.getEmail(),
                user.getDepartment(), user.getPhone(), user.getRole().name()
        );
    }
}
