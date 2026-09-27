package com.flashsale.security;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;
import java.util.Set;

/** Minimum password rules. Kept simple on purpose; length matters more than character classes. */
public final class PasswordPolicy {

    public static final String RULES = "at least 8 characters, with a letter and a number, and not containing your username";

    private static final Set<String> COMMON = Set.of(
            "password1", "password12", "password123", "passw0rd", "qwerty123", "abc12345", "abcd1234",
            "12345678a", "iloveyou1", "welcome1", "welcome123", "letmein1", "admin123", "changeme1");

    private PasswordPolicy() {}

    public static void check(String username, String password) {
        String lower = password.toLowerCase(Locale.ROOT);
        boolean ok = password.length() >= 8
                && password.chars().anyMatch(Character::isLetter)
                && password.chars().anyMatch(Character::isDigit)
                && !lower.contains(username.toLowerCase(Locale.ROOT))
                && !COMMON.contains(lower);
        if (!ok) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password must be " + RULES);
        }
    }
}
