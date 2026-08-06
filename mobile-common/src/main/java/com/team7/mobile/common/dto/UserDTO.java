package com.team7.mobile.common.dto;

/**
 * User profile DTO — returned by /api/users/me.
 */
public class UserDTO {

    private Long id;
    private String username;
    private String email;
    private String department;
    private String phone;
    private String role;

    public UserDTO() {}

    public UserDTO(Long id, String username, String email,
                   String department, String phone, String role) {
        this.id = id;
        this.username = username;
        this.email = email;
        this.department = department;
        this.phone = phone;
        this.role = role;
    }

    public Long getId() { return id; }
    public String getUsername() { return username; }
    public String getEmail() { return email; }
    public String getDepartment() { return department; }
    public String getPhone() { return phone; }
    public String getRole() { return role; }
}
