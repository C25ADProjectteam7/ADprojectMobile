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

    /**
     * Look up any user's basic profile by id.
     * Used by the frontend to display who submitted a trip/expense (name + department).
     * Any authenticated user can query (company-internal directory).
     */
    public UserDTO getUserById(Long id) {
        requireUser();  // must be authenticated
        User user = userRepository.findById(id)
                .orElseThrow(() -> new BusinessException("USER_NOT_FOUND", "User not found: " + id, 404));
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

    /**
     * Forgot password (public flow, no login required): the account is
     * verified by username + the four profile fields matching the backend
     * record exactly (school-project MVP - no email/SMS sending), then the
     * new password is set. The app copy states: "all account details must
     * match the backend record before the password can be changed".
     */
    public void forgotPassword(String username, String email, String department,
                               String phone, String newPassword) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new BusinessException("USER_NOT_FOUND",
                        "Account not found", 404));
        requireProfileMatch(user, email, department, phone);
        setNewPassword(user, newPassword);
    }

    /**
     * Reset / change password - the mobile app additionally sends the full
     * account details (username, email, department, phone) to confirm the
     * identity before accepting the old password. All of them must match.
     */
    public void changePassword(String username, String email, String department,
                               String phone, String oldPassword, String newPassword) {
        User user = requireUser();
        requireProfileMatch(user, email, department, phone);
        if (username != null && !username.isBlank()
                && !user.getUsername().equalsIgnoreCase(username.trim())) {
            throw new BusinessException("ACCOUNT_MISMATCH",
                    "Username does not match the signed-in account", 400);
        }
        if (!passwordEncoder.matches(oldPassword, user.getPassword())) {
            throw new BusinessException("INVALID_OLD_PASSWORD", "Old password is incorrect", 400);
        }
        setNewPassword(user, newPassword);
    }

    /** All four profile fields must match the stored record exactly. */
    private void requireProfileMatch(User user, String email, String department, String phone) {
        if (!equalsIgnoreCaseTrim(user.getEmail(), email)
                || !equalsIgnoreCaseTrim(user.getDepartment(), department)
                || !equalsIgnoreCaseTrim(user.getPhone(), phone)) {
            throw new BusinessException("ACCOUNT_MISMATCH",
                    "Account details do not match our records", 400);
        }
    }

    private boolean equalsIgnoreCaseTrim(String stored, String given) {
        if (stored == null || given == null) return false;
        return stored.trim().equalsIgnoreCase(given.trim());
    }

    private void setNewPassword(User user, String newPassword) {
        if (newPassword == null || newPassword.length() < 8) {
            throw new BusinessException("WEAK_PASSWORD",
                    "New password must be at least 8 characters", 400);
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
