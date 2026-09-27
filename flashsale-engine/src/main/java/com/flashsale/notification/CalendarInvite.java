package com.flashsale.notification;

import com.flashsale.seat.Event;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** iCalendar (RFC 5545) invite, understood by Google Calendar, Outlook and Apple Calendar. */
public final class CalendarInvite {

    private static final DateTimeFormatter UTC = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final Duration DEFAULT_LENGTH = Duration.ofHours(3);

    private CalendarInvite() {}

    public static String forBooking(String bookingId, Event event, String seatLabel) {
        return String.join("\r\n",
                "BEGIN:VCALENDAR",
                "VERSION:2.0",
                "PRODID:-//FlashSale//Tickets//EN",
                "METHOD:PUBLISH",
                "CALSCALE:GREGORIAN",
                "BEGIN:VEVENT",
                "UID:" + bookingId + "@flashsale",
                "DTSTAMP:" + UTC.format(Instant.now()),
                "DTSTART:" + UTC.format(event.startTime()),
                "DTEND:" + UTC.format(event.startTime().plus(DEFAULT_LENGTH)),
                "SUMMARY:" + escape(event.name() + " (" + seatLabel + ")"),
                "LOCATION:" + escape(event.venue()),
                "DESCRIPTION:" + escape("FlashSale booking " + bookingId + ". Show this email at the entrance."),
                "BEGIN:VALARM",
                "TRIGGER:-PT2H",
                "ACTION:DISPLAY",
                "DESCRIPTION:" + escape(event.name() + " starts in 2 hours"),
                "END:VALARM",
                "END:VEVENT",
                "END:VCALENDAR",
                "");
    }

    static String escape(String text) {
        return text.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\n", "\\n");
    }
}
