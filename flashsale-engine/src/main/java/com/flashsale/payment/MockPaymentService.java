package com.flashsale.payment;

import com.flashsale.infra.Topics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

/**
 * Stand-in for a payment provider. It only talks to the rest of the system through Kafka,
 * so it can later be split into its own service without changing the booking side.
 */
@Service
public class MockPaymentService {

    private static final Logger log = LoggerFactory.getLogger(MockPaymentService.class);

    private final PaymentRepository payments;
    private final MongoTemplate mongo;
    private final KafkaTemplate<String, String> kafka;
    private final JsonMapper json;
    private final Duration delay;
    private final Duration slowDelay;
    private final double randomSuccessRate;

    public MockPaymentService(PaymentRepository payments, MongoTemplate mongo, KafkaTemplate<String, String> kafka,
                              JsonMapper json,
                              @Value("${app.payment.delay:1s}") Duration delay,
                              @Value("${app.payment.slow-delay:8s}") Duration slowDelay,
                              @Value("${app.payment.random-success-rate:0.7}") double randomSuccessRate) {
        this.payments = payments;
        this.mongo = mongo;
        this.kafka = kafka;
        this.json = json;
        this.delay = delay;
        this.slowDelay = slowDelay;
        this.randomSuccessRate = randomSuccessRate;
    }

    @KafkaListener(topics = Topics.PAYMENT_REQUESTED, groupId = "payment-service")
    public void onPaymentRequested(String message) throws Exception {
        PaymentRequested request = json.readValue(message, PaymentRequested.class);
        if (request.isRefund()) {
            refund(request.bookingId(), request.reason());
            return;
        }

        Payment payment = payments.findByBookingId(request.bookingId()).orElse(null);
        if (payment == null) {
            sleep(request.outcome() == PaymentOutcome.SLOW ? slowDelay : delay);
            boolean ok = switch (request.outcome()) {
                case SUCCESS, SLOW -> true;
                case DECLINE -> false;
                case RANDOM -> ThreadLocalRandom.current().nextDouble() < randomSuccessRate;
            };
            try {
                payment = payments.insert(new Payment(null, request.bookingId(), request.amount(),
                        ok ? Payment.PaymentStatus.SUCCEEDED : Payment.PaymentStatus.FAILED,
                        ok ? null : "Card declined (mock)", Instant.now()));
            } catch (DuplicateKeyException redelivered) {
                payment = payments.findByBookingId(request.bookingId()).orElseThrow();
            }
        }
        // Redelivered request: publish the same result again instead of charging twice.
        boolean succeeded = payment.status() != Payment.PaymentStatus.FAILED;
        PaymentResult result = new PaymentResult(request.bookingId(), request.eventId(), request.seatNo(),
                request.userId(), payment.id(), succeeded, payment.reason());
        kafka.send(succeeded ? Topics.PAYMENT_SUCCEEDED : Topics.PAYMENT_FAILED,
                String.valueOf(request.eventId()), json.writeValueAsString(result)).get(5, TimeUnit.SECONDS);
        log.info("Payment {} for booking {}: {}", payment.id(), request.bookingId(), succeeded ? "SUCCEEDED" : "FAILED");
    }

    /**
     * Compensation command from the booking side (a paid booking that cannot be honoured).
     * Idempotent: only a SUCCEEDED payment moves to REFUNDED, so a redelivered command does nothing.
     * A real provider call (refund API) would go here.
     */
    private void refund(String bookingId, String reason) {
        long changed = mongo.updateFirst(
                query(where("bookingId").is(bookingId).and("status").is(Payment.PaymentStatus.SUCCEEDED)),
                new Update().set("status", Payment.PaymentStatus.REFUNDED).set("reason", reason).set("refundedAt", Instant.now()),
                Payment.class).getModifiedCount();
        if (changed == 1) {
            log.info("Refunded payment for booking {}: {}", bookingId, reason);
        } else if (payments.findByBookingId(bookingId).isEmpty()) {
            // The charge has not been recorded yet: fail so the command is retried, then dead-lettered.
            throw new IllegalStateException("No payment to refund for booking " + bookingId);
        }
    }

    private static void sleep(Duration d) throws InterruptedException {
        if (!d.isZero()) {
            Thread.sleep(d);
        }
    }
}
