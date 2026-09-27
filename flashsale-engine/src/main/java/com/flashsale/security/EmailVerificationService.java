package com.flashsale.security;

import com.flashsale.infra.OutboxService;
import com.flashsale.infra.Topics;
import com.flashsale.notification.NotificationRequested;
import com.flashsale.notification.NotificationType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

/**
 * Email ownership check. A random token goes out in a link; only its SHA-256 hash is stored, with a
 * TTL index, so a database leak cannot be used to verify addresses. Ticket emails are only sent to
 * verified addresses.
 */
@Service
public class EmailVerificationService {

    @Document("email_verifications")
    record Verification(@Id String tokenHash, String username, String email, Instant expiresAt) {}

    private static final SecureRandom RANDOM = new SecureRandom();

    private final MongoTemplate mongo;
    private final OutboxService outbox;
    private final TransactionTemplate tx;
    private final String appUrl;
    private final Duration validity;

    public EmailVerificationService(MongoTemplate mongo, OutboxService outbox, TransactionTemplate tx,
                                    @Value("${app.mail.app-url:http://localhost:8080}") String appUrl,
                                    @Value("${app.auth.email-verification-validity:24h}") Duration validity) {
        this.mongo = mongo;
        this.outbox = outbox;
        this.tx = tx;
        this.appUrl = appUrl;
        this.validity = validity;
    }

    /** New token (older ones for this user stop working) and the email, in one transaction. */
    public void start(User user) {
        if (user.email() == null || user.isEmailVerified()) {
            return;
        }
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        String hash = sha256(token);
        String link = appUrl + "/api/auth/verify-email?token=" + token;
        tx.executeWithoutResult(status -> {
            mongo.remove(query(where("username").is(user.username())), Verification.class);
            mongo.insert(new Verification(hash, user.username(), user.email(), Instant.now().plus(validity)));
            outbox.enqueue(Topics.NOTIFICATIONS, user.username(), "user-" + user.username(),
                    new NotificationRequested(NotificationType.EMAIL_VERIFICATION,
                            "EMAIL_VERIFICATION:" + user.username() + ":" + hash.substring(0, 16),
                            user.username(), 0, List.of(), null, null, null, link));
        });
    }

    /** @return true if the token was valid and the address is now verified */
    public boolean verify(String token) {
        if (token == null || token.isBlank() || token.length() > 100) {
            return false;
        }
        Verification v = mongo.findAndRemove(query(where("_id").is(sha256(token))), Verification.class);
        if (v == null || v.expiresAt().isBefore(Instant.now())) {
            return false;
        }
        // Only if the account still has the address the link was sent to.
        return mongo.updateFirst(query(where("username").is(v.username()).and("email").is(v.email())),
                new Update().set("emailVerified", true), User.class).getModifiedCount() == 1;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
