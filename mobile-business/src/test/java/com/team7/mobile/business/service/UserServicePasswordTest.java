package com.team7.mobile.business.service;

import com.team7.mobile.business.util.CurrentUser;
import com.team7.mobile.common.exception.BusinessException;
import com.team7.mobile.data.entity.User;
import com.team7.mobile.data.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the forgot-password / reset-password flows:
 * profile-field verification, old-password check, weak-password rejection.
 */
@ExtendWith(MockitoExtension.class)
class UserServicePasswordTest {

    @Mock private UserRepository userRepository;
    @Mock private CurrentUser currentUser;
    private final PasswordEncoder encoder = new BCryptPasswordEncoder();
    private UserService userService;
    private User user;

    @BeforeEach
    void setUp() {
        userService = new UserService(userRepository, currentUser, encoder);
        user = new User();
        user.setUsername("ashley.tan");
        user.setEmail("ashley@company.com.sg");
        user.setDepartment("Sales");
        user.setPhone("+65 8123 4567");
        user.setPassword(encoder.encode("old-pass-123"));
    }

    // ---------------------------------------------------------- forgot
    @Test
    void forgotPassword_allDetailsMatch_setsNewPassword() {
        when(userRepository.findByUsername("ashley.tan")).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenReturn(user);

        assertDoesNotThrow(() -> userService.forgotPassword(
                "ashley.tan", "ashley@company.com.sg", "Sales", "+65 8123 4567", "new-pass-456"));

        assertTrue(encoder.matches("new-pass-456", user.getPassword()));
        verify(userRepository).save(user);
    }

    @Test
    void forgotPassword_unknownUsername_throwsNotFound() {
        when(userRepository.findByUsername("nobody")).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.forgotPassword("nobody", "a@b.c", "D", "1", "new-pass-456"));
        assertEquals(404, ex.getHttpStatus());
    }

    @Test
    void forgotPassword_wrongEmail_rejected() {
        when(userRepository.findByUsername("ashley.tan")).thenReturn(Optional.of(user));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.forgotPassword(
                        "ashley.tan", "wrong@company.com.sg", "Sales", "+65 8123 4567", "new-pass-456"));
        assertEquals("ACCOUNT_MISMATCH", ex.getErrorCode());
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void forgotPassword_wrongDepartment_rejected() {
        when(userRepository.findByUsername("ashley.tan")).thenReturn(Optional.of(user));

        assertThrows(BusinessException.class,
                () -> userService.forgotPassword(
                        "ashley.tan", "ashley@company.com.sg", "Engineering", "+65 8123 4567", "new-pass-456"));
    }

    @Test
    void forgotPassword_wrongPhone_rejected() {
        when(userRepository.findByUsername("ashley.tan")).thenReturn(Optional.of(user));

        assertThrows(BusinessException.class,
                () -> userService.forgotPassword(
                        "ashley.tan", "ashley@company.com.sg", "Sales", "999", "new-pass-456"));
    }

    @Test
    void forgotPassword_caseInsensitiveDetailsAccepted() {
        when(userRepository.findByUsername("ashley.tan")).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenReturn(user);

        assertDoesNotThrow(() -> userService.forgotPassword(
                "ashley.tan", "ASHLEY@COMPANY.COM.SG", " sales ", "+65 8123 4567", "new-pass-456"));
    }

    @Test
    void forgotPassword_weakNewPassword_rejected() {
        when(userRepository.findByUsername("ashley.tan")).thenReturn(Optional.of(user));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.forgotPassword(
                        "ashley.tan", "ashley@company.com.sg", "Sales", "+65 8123 4567", "short"));
        assertEquals("WEAK_PASSWORD", ex.getErrorCode());
    }

    // ---------------------------------------------------------- reset
    @Test
    void changePassword_allFieldsAndOldPasswordMatch_updatesPassword() {
        when(currentUser.get()).thenReturn(user);
        when(userRepository.save(any(User.class))).thenReturn(user);

        assertDoesNotThrow(() -> userService.changePassword(
                "ashley.tan", "ashley@company.com.sg", "Sales", "+65 8123 4567",
                "old-pass-123", "brand-new-789"));

        assertTrue(encoder.matches("brand-new-789", user.getPassword()));
    }

    @Test
    void changePassword_wrongOldPassword_rejected() {
        when(currentUser.get()).thenReturn(user);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.changePassword(
                        "ashley.tan", "ashley@company.com.sg", "Sales", "+65 8123 4567",
                        "wrong-old", "brand-new-789"));
        assertEquals("INVALID_OLD_PASSWORD", ex.getErrorCode());
    }

    @Test
    void changePassword_profileMismatch_rejectedEvenWithCorrectOldPassword() {
        when(currentUser.get()).thenReturn(user);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.changePassword(
                        "ashley.tan", "other@company.com.sg", "Sales", "+65 8123 4567",
                        "old-pass-123", "brand-new-789"));
        assertEquals("ACCOUNT_MISMATCH", ex.getErrorCode());
        verify(userRepository, never()).save(any(User.class));
    }
}
