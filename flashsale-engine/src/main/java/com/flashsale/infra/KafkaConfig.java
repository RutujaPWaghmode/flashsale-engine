package com.flashsale.infra;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaConfig {

    @Bean
    KafkaAdmin.NewTopics topics(@Value("${app.kafka.partitions:3}") int partitions,
                                @Value("${app.kafka.replicas:1}") int replicas) {
        return new KafkaAdmin.NewTopics(
                topic(Topics.PAYMENT_REQUESTED, partitions, replicas),
                topic(Topics.PAYMENT_SUCCEEDED, partitions, replicas),
                topic(Topics.PAYMENT_FAILED, partitions, replicas),
                topic(Topics.NOTIFICATIONS, partitions, replicas),
                topic(Topics.BOOKING_DLQ, 1, replicas));
    }

    /**
     * Retry a failing record 3 times, 1 second apart, then park it on the dead-letter topic so one
     * poison message cannot block its partition. Spring Boot applies this handler to every listener.
     */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
                (record, ex) -> new TopicPartition(Topics.BOOKING_DLQ, -1));
        return new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3));
    }

    private static NewTopic topic(String name, int partitions, int replicas) {
        return TopicBuilder.name(name).partitions(partitions).replicas(replicas).build();
    }
}
