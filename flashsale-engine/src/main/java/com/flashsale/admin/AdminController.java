package com.flashsale.admin;

import com.flashsale.booking.ReconciliationJob;
import com.flashsale.notification.EventReminderJob;
import com.flashsale.seat.EventController;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** ADMIN role only (see SecurityConfig). */
@RestController
@RequestMapping("/api/admin/events")
public class AdminController {

    private final EventResetService resetService;
    private final ReconciliationJob reconciliation;
    private final EventAdminService eventAdmin;
    private final EventController eventController;
    private final EventReminderJob reminders;

    public AdminController(EventResetService resetService, ReconciliationJob reconciliation,
                           EventAdminService eventAdmin, EventController eventController, EventReminderJob reminders) {
        this.resetService = resetService;
        this.reconciliation = reconciliation;
        this.eventAdmin = eventAdmin;
        this.eventController = eventController;
        this.reminders = reminders;
    }

    @PostMapping
    public ResponseEntity<EventController.EventView> create(@Valid @RequestBody EventAdminService.CreateEventRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(eventController.view(eventAdmin.create(request)));
    }

    @PostMapping("/{eventId}/reset")
    public ResponseEntity<Void> reset(@PathVariable long eventId) {
        resetService.reset(eventId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{eventId}/reconcile")
    public Map<String, Integer> reconcile(@PathVariable long eventId) {
        return Map.of("repairedSeats", reconciliation.reconcile(eventId));
    }

    /** Sends the reminder emails now instead of waiting for 24 h before the event (demo). */
    @PostMapping("/{eventId}/remind")
    public Map<String, Integer> remind(@PathVariable long eventId) {
        return Map.of("remindersQueued", reminders.remind(eventId));
    }
}
