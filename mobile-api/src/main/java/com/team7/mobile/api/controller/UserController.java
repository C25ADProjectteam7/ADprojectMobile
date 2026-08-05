package com.team7.mobile.api.controller;

import com.team7.mobile.common.dto.ApiResponse;
import com.team7.mobile.common.dto.UserDTO;
import com.team7.mobile.business.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    /** Current user's profile. */
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<UserDTO>> getMe() {
        return ResponseEntity.ok(ApiResponse.success(userService.getMe()));
    }

    /** Update own profile. */
    @PutMapping("/me")
    public ResponseEntity<ApiResponse<UserDTO>> updateMe(@RequestBody Map<String, String> fields) {
        return ResponseEntity.ok(ApiResponse.success("Profile updated", userService.updateMe(fields)));
    }

    /** Change password. Body: { "oldPassword": "...", "newPassword": "..." } */
    @PutMapping("/me/password")
    public ResponseEntity<ApiResponse<Void>> changePassword(@RequestBody Map<String, String> body) {
        userService.changePassword(body.get("oldPassword"), body.get("newPassword"));
        return ResponseEntity.ok(ApiResponse.success("Password changed", null));
    }
}
