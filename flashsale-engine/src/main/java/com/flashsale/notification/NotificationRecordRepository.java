package com.flashsale.notification;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface NotificationRecordRepository extends MongoRepository<NotificationRecord, String> {

    void deleteByEventId(long eventId);
}
