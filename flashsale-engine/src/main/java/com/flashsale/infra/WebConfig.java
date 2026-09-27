package com.flashsale.infra;

import com.flashsale.common.RateLimitInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final RateLimitInterceptor rateLimit;

    public WebConfig(RateLimitInterceptor rateLimit) {
        this.rateLimit = rateLimit;
    }

    /** Booking endpoints and the DB baselines pay the same rate-limit cost, so benchmarks stay fair. */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimit).addPathPatterns("/api/bookings", "/api/bookings/**", "/api/baseline/**");
    }
}
