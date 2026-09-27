package com.flashsale.common;

import com.flashsale.infra.FlashsaleMetrics;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.security.Principal;
import java.time.Duration;
import java.time.Instant;

/**
 * Per-user fixed-window limiter (requests per second) in Redis, so the limit holds across
 * every app instance. Applies to POSTs only: reads are cheap and safe.
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private final StringRedisTemplate redis;
    private final FlashsaleMetrics metrics;
    private final int limitPerSecond;

    public RateLimitInterceptor(StringRedisTemplate redis, FlashsaleMetrics metrics,
                                @Value("${app.rate-limit.per-user-per-second:10}") int limitPerSecond) {
        this.redis = redis;
        this.metrics = metrics;
        this.limitPerSecond = limitPerSecond;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        Principal user = request.getUserPrincipal();
        if (!"POST".equals(request.getMethod()) || user == null) {
            return true;
        }
        String key = "rl:" + user.getName() + ":" + Instant.now().getEpochSecond();
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1) {
            redis.expire(key, Duration.ofSeconds(2));
        }
        if (count != null && count > limitPerSecond) {
            metrics.rejected("RATE_LIMITED");
            response.setStatus(429);
            response.setHeader("Retry-After", "1");
            response.setContentType("application/problem+json");
            response.getWriter().write("{\"title\":\"Too Many Requests\",\"status\":429,"
                    + "\"detail\":\"Rate limit is " + limitPerSecond + " requests per second\"}");
            return false;
        }
        return true;
    }
}
