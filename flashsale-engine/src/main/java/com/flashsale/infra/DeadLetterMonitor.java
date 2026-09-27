package com.flashsale.infra;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/** Counts and logs dead-lettered messages. Replaying them is a manual, deliberate step. */
@Component
public class DeadLetterMonitor {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterMonitor.class);

    private final FlashsaleMetrics metrics;

    public DeadLetterMonitor(FlashsaleMetrics metrics) {
        this.metrics = metrics;
    }

    @KafkaListener(topics = Topics.BOOKING_DLQ, groupId = "dlq-monitor")
    public void onDeadLetter(ConsumerRecord<String, String> record) {
        String originalTopic = header(record, KafkaHeaders.DLT_ORIGINAL_TOPIC);
        metrics.deadLettered(originalTopic != null ? originalTopic : "unknown");
        log.error("Dead-lettered message from {}: {} (cause: {})",
                originalTopic, record.value(), header(record, KafkaHeaders.DLT_EXCEPTION_MESSAGE));
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header != null ? new String(header.value(), StandardCharsets.UTF_8) : null;
    }
}
