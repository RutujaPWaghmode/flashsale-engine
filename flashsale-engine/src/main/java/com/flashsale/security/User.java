package com.flashsale.security;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.List;

/**
 * {@code email} is optional (load-test users have none). Ticket emails are sent only when
 * {@code emailVerified} is true; accounts created before verification existed read as false.
 */
@Document("users")
public record User(@Id String id, String username, String passwordHash, List<String> roles, String email,
                   Boolean emailVerified) {

    /** Missing in documents created before verification existed: treated as not verified. */
    public boolean isEmailVerified() {
        return Boolean.TRUE.equals(emailVerified);
    }


    public boolean isAdmin() {
        return roles != null && roles.contains("ADMIN");
    }
}
