package com.flashsale.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import com.flashsale.common.TooManyRequestsException;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/**
 * Brute-force protection in Redis, shared by every app instance.
 *
 * <ul>
 *   <li>Account lock: {@code maxFailures} wrong passwords for a username within {@code lockDuration}
 *       lock it for {@code lockDuration}, even for the right password.</li>
 *   <li>Per-IP: at most {@code failuresPerIpPerMinute} failed logins per minute from one address,
 *       so one client cannot spray passwords across many accounts.</li>
 *   <li>Per-IP sign-ups: at most {@code registrationsPerIpPerHour} new accounts per hour.</li>
 * </ul>
 * Only failures (and successful sign-ups) are counted, so normal use and load tests are not slowed.
 */
@Component
public class LoginThrottle {

    private final StringRedisTemplate redis;
    private final int maxFailures;
    private final Duration lockDuration;
    private final int failuresPerIpPerMinute;
    private final int registrationsPerIpPerHour;

    public LoginThrottle(StringRedisTemplate redis,
                         @Value("${app.auth.max-failed-logins:5}") int maxFailures,
                         @Value("${app.auth.lock-duration:15m}") Duration lockDuration,
                         @Value("${app.auth.failed-logins-per-ip-per-minute:20}") int failuresPerIpPerMinute,
                         @Value("${app.auth.registrations-per-ip-per-hour:20}") int registrationsPerIpPerHour) {
        this.redis = redis;
        this.maxFailures = maxFailures;
        this.lockDuration = lockDuration;
        this.failuresPerIpPerMinute = failuresPerIpPerMinute;
        this.registrationsPerIpPerHour = registrationsPerIpPerHour;
    }

    /** Before checking the password. */
    public void checkLogin(String username, String ip) {
        Long lockSeconds = redis.getExpire(lockKey(username));
        if (lockSeconds != null && lockSeconds > 0) {
            throw tooMany("Too many failed attempts. Try again in " + minutes(lockSeconds) + ".", lockSeconds);
        }
        if (count(ipFailuresKey(ip)) >= failuresPerIpPerMinute) {
            throw tooMany("Too many failed sign-in attempts from your network. Try again in a minute.", 60);
        }
    }

    public void loginFailed(String username, String ip) {
        increment(ipFailuresKey(ip), Duration.ofMinutes(2));
        long failures = increment(failuresKey(username), lockDuration);
        if (failures >= maxFailures) {
            redis.opsForValue().set(lockKey(username), "1", lockDuration);
            redis.delete(failuresKey(username));
        }
    }

    public void loginSucceeded(String username) {
        redis.delete(failuresKey(username));
    }

    public void checkRegistration(String ip) {
        if (count(registrationsKey(ip)) >= registrationsPerIpPerHour) {
            throw tooMany("Too many new accounts from your network. Try again later.", 3600);
        }
    }

    public void registered(String ip) {
        increment(registrationsKey(ip), Duration.ofHours(2));
    }

    /** Generic per-key limit, e.g. resending verification emails. */
    public void limit(String name, int max, Duration window) {
        String key = "limit:" + name + ":" + Instant.now().getEpochSecond() / window.toSeconds();
        if (increment(key, window.multipliedBy(2)) > max) {
            throw tooMany("Please wait a little before trying again.", window.toSeconds());
        }
    }

    private long count(String key) {
        String value = redis.opsForValue().get(key);
        return value == null ? 0 : Long.parseLong(value);
    }

    private long increment(String key, Duration ttl) {
        Long value = redis.opsForValue().increment(key);
        if (value != null && value == 1) {
            redis.expire(key, ttl);
        }
        return value == null ? 0 : value;
    }

    private static String failuresKey(String username) { return "login-fail:user:" + norm(username); }
    private static String lockKey(String username)     { return "login-lock:" + norm(username); }
    private static String ipFailuresKey(String ip)     { return "login-fail:ip:" + ip + ":" + Instant.now().getEpochSecond() / 60; }
    private static String registrationsKey(String ip)  { return "register:ip:" + ip + ":" + Instant.now().getEpochSecond() / 3600; }

    private static String norm(String username) {
        return username.trim().toLowerCase(Locale.ROOT);
    }

    private static String minutes(long seconds) {
        long m = (seconds + 59) / 60;
        return m + (m == 1 ? " minute" : " minutes");
    }

    private static TooManyRequestsException tooMany(String message, long retryAfterSeconds) {
        return new TooManyRequestsException(message, retryAfterSeconds);
    }
}
