package com.flashsale.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public record Credentials(@NotBlank @Size(max = 64) String username,
                              @NotBlank @Size(max = 128) String password) {}

    public record RegisterRequest(@NotBlank @Size(min = 3, max = 32)
                                  @Pattern(regexp = "[A-Za-z0-9._-]+", message = "may contain letters, digits, dot, dash and underscore")
                                  String username,
                                  @NotBlank @Size(max = 128) String password,
                                  @Email @Size(max = 254) String email) {}

    /** {@code token} is for API clients; the browser uses the httpOnly cookie and ignores it. */
    public record TokenResponse(String token, Me user) {}

    public record Me(String username, boolean admin, String email, boolean emailVerified, Instant expiresAt) {}

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final LoginThrottle throttle;
    private final AuthCookies cookies;
    private final EmailVerificationService verification;
    /** Compared against when the username does not exist, so both cases take the same time. */
    private final String dummyHash;

    public AuthController(UserRepository users, PasswordEncoder passwordEncoder, JwtService jwtService,
                          LoginThrottle throttle, AuthCookies cookies, EmailVerificationService verification) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.throttle = throttle;
        this.cookies = cookies;
        this.verification = verification;
        this.dummyHash = passwordEncoder.encode("timing-equaliser-not-a-password-1");
    }

    @PostMapping("/register")
    public ResponseEntity<Void> register(@Valid @RequestBody RegisterRequest request, HttpServletRequest http) {
        String ip = http.getRemoteAddr();
        throttle.checkRegistration(ip);
        PasswordPolicy.check(request.username(), request.password());
        String email = request.email() == null || request.email().isBlank() ? null : request.email().trim();
        User user = new User(null, request.username(), passwordEncoder.encode(request.password()), List.of("USER"), email, false);
        try {
            user = users.insert(user);
        } catch (DuplicateKeyException taken) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already taken");
        }
        throttle.registered(ip);
        verification.start(user);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody Credentials credentials, HttpServletRequest http) {
        String ip = http.getRemoteAddr();
        throttle.checkLogin(credentials.username(), ip);

        User user = users.findByUsername(credentials.username()).orElse(null);
        boolean matches = passwordEncoder.matches(credentials.password(), user != null ? user.passwordHash() : dummyHash);
        if (user == null || !matches) {
            throttle.loginFailed(credentials.username(), ip);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password");
        }
        throttle.loginSucceeded(credentials.username());

        String token = jwtService.issue(user);
        Duration ttl = jwtService.ttl();
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookies.issue(token, ttl).toString())
                .body(new TokenResponse(token, me(user, Instant.now().plus(ttl))));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookies.clear().toString()).build();
    }

    @GetMapping("/me")
    public Me me(@AuthenticationPrincipal Jwt jwt) {
        User user = users.findByUsername(jwt.getSubject())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Account no longer exists"));
        return me(user, jwt.getExpiresAt());
    }

    /** The link in the verification email. Redirects back to the UI, which shows the result. */
    @GetMapping("/verify-email")
    public ResponseEntity<Void> verifyEmail(@RequestParam(required = false) String token) {
        String target = verification.verify(token) ? "/#email-verified" : "/#email-verify-failed";
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(target)).build();
    }

    @PostMapping("/resend-verification")
    public ResponseEntity<Void> resendVerification(@AuthenticationPrincipal Jwt jwt) {
        throttle.limit("resend-verification:" + jwt.getSubject(), 3, Duration.ofHours(1));
        User user = users.findByUsername(jwt.getSubject())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Account no longer exists"));
        if (user.email() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No email address on this account");
        }
        verification.start(user);
        return ResponseEntity.accepted().build();
    }

    private static Me me(User user, Instant expiresAt) {
        return new Me(user.username(), user.isAdmin(), user.email(), user.isEmailVerified(), expiresAt);
    }
}
