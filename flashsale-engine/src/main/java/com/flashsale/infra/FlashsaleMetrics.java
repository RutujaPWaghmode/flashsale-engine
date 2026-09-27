package com.flashsale.infra;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** Business counters, exposed at /actuator/prometheus as bookings_*_total etc. */
@Component
public class FlashsaleMetrics {

    private final MeterRegistry registry;

    public FlashsaleMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void held()                    { registry.counter("bookings.held").increment(); }
    public void rejected(String reason)   { registry.counter("bookings.rejected", "reason", reason).increment(); }
    public void confirmed()               { registry.counter("bookings.confirmed").increment(); }
    public void failed()                  { registry.counter("bookings.failed").increment(); }
    public void expired()                 { registry.counter("bookings.expired").increment(); }
    public void cancelled()               { registry.counter("bookings.cancelled").increment(); }
    public void refunded(String reason)   { registry.counter("bookings.refunded", "reason", reason).increment(); }
    public void oversellBlocked()         { registry.counter("bookings.oversell.blocked").increment(); }
    public void drift(String type)        { registry.counter("reconciliation.drift", "type", type).increment(); }
    public void deadLettered(String from) { registry.counter("kafka.dlq.messages", "topic", from).increment(); }
    public void outboxPublishFailed()     { registry.counter("outbox.publish.failures").increment(); }
}
