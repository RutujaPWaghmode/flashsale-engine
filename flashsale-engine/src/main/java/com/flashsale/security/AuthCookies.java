package com.flashsale.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseCookie;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;

/**
 * The browser keeps the JWT in an httpOnly cookie: page scripts (and so injected scripts) can never
 * read it. API clients (k6, curl, tests) keep using "Authorization: Bearer ...".
 *
 * CSRF: a cookie is sent automatically, so for state-changing requests the cookie is only accepted
 * together with the {@code X-Requested-With: FlashSale} header. A cross-site page cannot add a custom
 * header without a CORS preflight, which this app never allows. SameSite=Strict is a second layer.
 */
@Component
public class AuthCookies implements BearerTokenResolver {

    public static final String NAME = "flashsale_token";
    public static final String CSRF_HEADER = "X-Requested-With";
    public static final String CSRF_VALUE = "FlashSale";
    private static final Set<String> SAFE_METHODS = Set.of(HttpMethod.GET.name(), HttpMethod.HEAD.name(), HttpMethod.OPTIONS.name());

    private final DefaultBearerTokenResolver header = new DefaultBearerTokenResolver();
    private final boolean secure;

    public AuthCookies(@Value("${app.auth.cookie-secure:false}") boolean secure) {
        this.secure = secure;
    }

    @Override
    public String resolve(HttpServletRequest request) {
        String fromHeader = header.resolve(request);
        if (fromHeader != null) {
            return fromHeader;
        }
        String fromCookie = cookie(request);
        if (fromCookie == null) {
            return null;
        }
        boolean safe = SAFE_METHODS.contains(request.getMethod());
        return safe || CSRF_VALUE.equals(request.getHeader(CSRF_HEADER)) ? fromCookie : null;
    }

    public ResponseCookie issue(String token, Duration ttl) {
        return base(token).maxAge(ttl).build();
    }

    public ResponseCookie clear() {
        return base("").maxAge(0).build();
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(NAME, value).httpOnly(true).secure(secure).sameSite("Strict").path("/");
    }

    private static String cookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie c : cookies) {
            if (NAME.equals(c.getName()) && !c.getValue().isBlank()) {
                return c.getValue();
            }
        }
        return null;
    }
}
