package com.flashsale.booking;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param holdTtl                how long a seat stays held before payment starts
 * @param paymentHoldExtension   minimum hold time left once payment starts
 * @param maxSeatsPerUser        per-user cap per event (anti-hoarding)
 */
@ConfigurationProperties("app.booking")
public record BookingProperties(@DefaultValue("5m") Duration holdTtl,
                                @DefaultValue("2m") Duration paymentHoldExtension,
                                @DefaultValue("4") int maxSeatsPerUser) {}
