package com.flashsale.security;

import com.flashsale.notification.NotificationRecord;
import com.flashsale.notification.NotificationRecordRepository;
import com.flashsale.notification.NotificationRequested;
import com.flashsale.notification.NotificationService;
import com.flashsale.notification.NotificationType;
import com.flashsale.support.IntegrationTest;
import jakarta.mail.Message;
import jakarta.mail.internet.MimeMessage;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Login protection, cookie auth + CSRF guard, security headers, password rules, email verification. */
class AuthSecurityTest extends IntegrationTest {

    private static final String PASSWORD = "Correct-horse9";

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired StringRedisTemplate redis;
    @Autowired NotificationService notificationService;
    @Autowired NotificationRecordRepository notificationLog;
    @Autowired JsonMapper json;

    // ---------- password rules ----------

    @Test
    void weakPasswords_areRejected() throws Exception {
        for (String weak : List.of("short1", "nodigitshere", "12345678", "password123")) {
            assertThat(register(name(), weak, null).getStatus()).as(weak).isEqualTo(400);
        }
        String user = name();
        assertThat(register(user, user + "99x", null).getStatus()).as("contains username").isEqualTo(400);
        assertThat(register(name(), PASSWORD, null).getStatus()).isEqualTo(201);
    }

    // ---------- login protection ----------

    @Test
    void fiveWrongPasswords_lockTheAccount_evenForTheRightPassword() throws Exception {
        String user = name();
        register(user, PASSWORD, null);
        for (int i = 0; i < 5; i++) {
            assertThat(login(user, "Wrong-pass" + i).getStatus()).isEqualTo(401);
        }
        MockHttpServletResponse locked = login(user, PASSWORD);
        assertThat(locked.getStatus()).isEqualTo(429);
        assertThat(locked.getHeader("Retry-After")).isNotNull();
    }

    @Test
    void successfulLogin_resetsTheFailureCount() throws Exception {
        String user = name();
        register(user, PASSWORD, null);
        for (int i = 0; i < 4; i++) login(user, "Wrong-pass" + i);
        assertThat(login(user, PASSWORD).getStatus()).isEqualTo(200);
        for (int i = 0; i < 4; i++) login(user, "Wrong-pass" + i);
        assertThat(login(user, PASSWORD).getStatus()).isEqualTo(200);   // 4 + 4 failures never reach 5 in a row
    }

    @Test
    void unknownUser_getsTheSameAnswerAsAWrongPassword() throws Exception {
        MockHttpServletResponse unknown = login("no-such-user-" + UUID.randomUUID(), PASSWORD);
        assertThat(unknown.getStatus()).isEqualTo(401);
        assertThat(unknown.getContentAsString()).contains("Invalid username or password");
    }

    @Test
    void perIpLimits_applyToFailedLoginsAndSignUps() {
        String ip = "203.0.113." + (int) (Math.random() * 200);
        LoginThrottle strict = new LoginThrottle(redis, 5, Duration.ofMinutes(15), 3, 2);
        for (int i = 0; i < 3; i++) strict.loginFailed(name(), ip);
        assertThatThrownBy(() -> strict.checkLogin(name(), ip)).hasMessageContaining("429");

        strict.registered(ip);
        strict.registered(ip);
        assertThatThrownBy(() -> strict.checkRegistration(ip)).hasMessageContaining("429");
    }

    // ---------- cookie auth ----------

    @Test
    void login_setsHttpOnlyStrictCookie_andMeWorksWithIt() throws Exception {
        String user = name();
        register(user, PASSWORD, null);
        MockHttpServletResponse res = login(user, PASSWORD);

        String setCookie = res.getHeader("Set-Cookie");
        assertThat(setCookie).contains(AuthCookies.NAME + "=").contains("HttpOnly").contains("SameSite=Strict");
        Cookie cookie = res.getCookie(AuthCookies.NAME);

        MockHttpServletResponse me = mvc.perform(get("/api/auth/me").cookie(cookie)).andReturn().getResponse();
        assertThat(me.getStatus()).isEqualTo(200);
        assertThat(me.getContentAsString()).contains("\"username\":\"" + user + "\"");
    }

    @Test
    void cookieWithoutCsrfHeader_cannotChangeState_butBearerAndHeaderCan() throws Exception {
        String user = name();
        register(user, PASSWORD, null);
        MockHttpServletResponse res = login(user, PASSWORD);
        Cookie cookie = res.getCookie(AuthCookies.NAME);
        String token = json.readTree(res.getContentAsString()).get("token").asString();
        String body = "{\"eventId\":1,\"seatNo\":\"H01\"}";

        // A forged cross-site form post would carry the cookie but not the custom header.
        assertThat(mvc.perform(post("/api/bookings").cookie(cookie).header("Idempotency-Key", "k1")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getStatus()).isEqualTo(401);

        assertThat(mvc.perform(post("/api/bookings").cookie(cookie).header("Idempotency-Key", "k2")
                .header(AuthCookies.CSRF_HEADER, AuthCookies.CSRF_VALUE)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getStatus()).isEqualTo(200);

        assertThat(mvc.perform(post("/api/bookings").header("Authorization", "Bearer " + token).header("Idempotency-Key", "k3")
                .contentType(MediaType.APPLICATION_JSON).content("{\"eventId\":1,\"seatNo\":\"H02\"}"))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void logout_clearsTheCookie() throws Exception {
        MockHttpServletResponse res = mvc.perform(post("/api/auth/logout")).andReturn().getResponse();
        assertThat(res.getStatus()).isEqualTo(204);
        assertThat(res.getHeader("Set-Cookie")).contains(AuthCookies.NAME + "=").contains("Max-Age=0");
    }

    @Test
    void pages_carrySecurityHeaders() throws Exception {
        MockHttpServletResponse res = mvc.perform(get("/")).andReturn().getResponse();
        assertThat(res.getHeader("Content-Security-Policy")).contains("script-src 'self'").contains("frame-ancestors 'none'");
        assertThat(res.getHeader("X-Frame-Options")).isEqualTo("DENY");
        assertThat(res.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    }

    // ---------- email verification ----------

    @Test
    void signUpEmail_containsAWorkingVerificationLink() throws Exception {
        String user = name();
        String email = user + "@example.com";
        assertThat(register(user, PASSWORD, email).getStatus()).isEqualTo(201);
        assertThat(users.findByUsername(user).orElseThrow().isEmailVerified()).isFalse();

        await("verification email", () -> !sentTo(email).isEmpty());
        String raw = raw(sentTo(email).getFirst()).replace("=\r\n", "").replace("=3D", "=");
        Matcher m = java.util.regex.Pattern.compile("verify-email\\?token=([A-Za-z0-9_-]{43})").matcher(raw);
        assertThat(m.find()).as("link in email").isTrue();

        MockHttpServletResponse bad = mvc.perform(get("/api/auth/verify-email").param("token", "nope")).andReturn().getResponse();
        assertThat(bad.getRedirectedUrl()).isEqualTo("/#email-verify-failed");

        MockHttpServletResponse ok = mvc.perform(get("/api/auth/verify-email").param("token", m.group(1))).andReturn().getResponse();
        assertThat(ok.getStatus()).isEqualTo(302);
        assertThat(ok.getRedirectedUrl()).isEqualTo("/#email-verified");
        assertThat(users.findByUsername(user).orElseThrow().isEmailVerified()).isTrue();

        MockHttpServletResponse reused = mvc.perform(get("/api/auth/verify-email").param("token", m.group(1))).andReturn().getResponse();
        assertThat(reused.getRedirectedUrl()).as("one-time link").isEqualTo("/#email-verify-failed");
    }

    @Test
    void ticketEmails_areNotSentToUnverifiedAddresses() throws Exception {
        String user = name();
        users.insert(new User(null, user, "{noop}x", List.of("USER"), user + "@example.com", false));
        String key = "BOOKING_CONFIRMED:test-" + UUID.randomUUID();

        notificationService.onNotification(json.writeValueAsString(new NotificationRequested(
                NotificationType.BOOKING_CONFIRMED, key, user, EVENT_ID, List.of("A01"), "b-x", null, null, null)));

        NotificationRecord record = notificationLog.findById(key).orElseThrow();
        assertThat(record.status()).isEqualTo(NotificationRecord.Status.SKIPPED);
        assertThat(record.detail()).isEqualTo("email address not verified");
        assertThat(sentTo(user + "@example.com")).isEmpty();
    }

    // ---------- helpers ----------

    private static String name() {
        return "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private MockHttpServletResponse register(String user, String password, String email) throws Exception {
        String body = email == null
                ? "{\"username\":\"%s\",\"password\":\"%s\"}".formatted(user, password)
                : "{\"username\":\"%s\",\"password\":\"%s\",\"email\":\"%s\"}".formatted(user, password, email);
        return mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
    }

    private MockHttpServletResponse login(String user, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(user, password))).andReturn().getResponse();
    }

    private List<MimeMessage> sentTo(String email) {
        return Mockito.mockingDetails(mailSender).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("send") && i.getArguments().length == 1
                        && i.getArguments()[0] instanceof MimeMessage)
                .map(i -> (MimeMessage) i.getArguments()[0])
                .filter(msg -> {
                    try {
                        return Arrays.stream(msg.getRecipients(Message.RecipientType.TO)).anyMatch(a -> a.toString().equals(email));
                    } catch (Exception e) {
                        return false;
                    }
                })
                .toList();
    }

    private static String raw(MimeMessage m) throws Exception {
        m.saveChanges();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        m.writeTo(out);
        return out.toString(StandardCharsets.UTF_8);
    }
}
