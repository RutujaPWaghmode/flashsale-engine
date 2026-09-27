package com.flashsale.support;

import com.flashsale.admin.EventResetService;
import com.flashsale.infra.Topics;
import com.flashsale.seat.DataSeeder;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.function.BooleanSupplier;

import static org.mockito.Mockito.when;

/**
 * Integration tests: in-memory Kafka, plus the local MongoDB (flashsale_test database, replica set rs0)
 * and Redis (database 1). Start them first with scripts/start-local.sh.
 * Skip with: ./mvnw test -DexcludedGroups=integration
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
// One offsets partition: group coordination is ready at once instead of loading 50 partitions,
// which on a loaded machine sometimes never finished and left every consumer group unjoined.
@EmbeddedKafka(partitions = 1, bootstrapServersProperty = "spring.kafka.bootstrap-servers",
        topics = {Topics.PAYMENT_REQUESTED, Topics.PAYMENT_SUCCEEDED, Topics.PAYMENT_FAILED, Topics.NOTIFICATIONS,
                Topics.BOOKING_DLQ},
        brokerProperties = {"offsets.topic.num.partitions=1", "offsets.topic.replication.factor=1",
                "group.initial.rebalance.delay.ms=0", "transaction.state.log.replication.factor=1"})
@Tag("integration")
public abstract class IntegrationTest {

    protected static final long EVENT_ID = DataSeeder.DEMO_EVENT_ID;

    @Autowired
    protected EventResetService resetService;

    /** No SMTP server in tests: emails are captured by this mock instead. */
    @MockitoBean
    protected JavaMailSender mailSender;

    @BeforeEach
    void resetEvent() {
        when(mailSender.createMimeMessage()).thenAnswer(inv -> new MimeMessage((Session) null));
        resetService.reset(EVENT_ID);
    }

    protected static void await(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Timed out waiting for: " + what);
    }
}
