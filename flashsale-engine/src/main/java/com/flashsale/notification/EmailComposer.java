package com.flashsale.notification;

import com.flashsale.seat.Event;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** Turns a notification into subject, plain text, HTML and (for confirmations) a calendar invite. */
@Component
public class EmailComposer {

    public record Email(String subject, String text, String html, String calendarInvite) {}

    private final DateTimeFormatter when;
    private final NumberFormat money = NumberFormat.getCurrencyInstance(Locale.of("en", "IN"));
    private final String appUrl;

    public EmailComposer(@Value("${app.mail.timezone:Asia/Kolkata}") String timezone,
                         @Value("${app.mail.app-url:http://localhost:8080}") String appUrl) {
        this.when = DateTimeFormatter.ofPattern("EEE, d MMM yyyy 'at' h:mm a z", Locale.ENGLISH).withZone(ZoneId.of(timezone));
        this.appUrl = appUrl;
    }

    public Email compose(NotificationRequested n, String username, Event event) {
        String seats = seatList(n.seatNos());
        String date = when.format(event.startTime());
        return switch (n.type()) {
            case BOOKING_CONFIRMED -> email(
                    "Your ticket for " + event.name() + " is confirmed",
                    "Hi " + username + ",",
                    List.of("Your booking is confirmed. See you there!"),
                    event, date, seats, n.amount(), "Booking " + n.bookingId(),
                    CalendarInvite.forBooking(n.bookingId(), event, seats));
            case PAYMENT_FAILED -> email(
                    "Payment declined for " + event.name(),
                    "Hi " + username + ",",
                    List.of("Your payment was declined (" + orElse(n.reason(), "no reason given") + ").",
                            "The seat has been released and you have not been charged. You can try again while seats last."),
                    event, date, seats, n.amount(), null, null);
            case BOOKING_REFUNDED -> email(
                    "Refund for " + event.name(),
                    "Hi " + username + ",",
                    List.of("We could not complete your booking: " + orElse(n.reason(), "the seat was no longer available") + ".",
                            "Your payment has been refunded in full."),
                    event, date, seats, n.amount(), "Booking " + n.bookingId(), null);
            case HOLD_EXPIRED -> email(
                    "Your hold on " + event.name() + " expired",
                    "Hi " + username + ",",
                    List.of("You did not complete payment in time, so the seat went back on sale.",
                            "If it is still free you can grab it again."),
                    event, date, seats, null, null, null);
            case EVENT_REMINDER -> email(
                    "Reminder: " + event.name() + " is coming up",
                    "Hi " + username + ",",
                    List.of("Just a reminder that your event is coming up soon."),
                    event, date, seats, null, null, null);
            case EMAIL_VERIFICATION -> throw new IllegalArgumentException("Use verification()");
        };
    }

    public Email verification(String username, String link) {
        String subject = "Confirm your email for FlashSale";
        String text = "Hi " + username + ",\n\nConfirm your email address so we can send you your tickets:\n\n"
                + link + "\n\nThe link works for 24 hours. If you did not create a FlashSale account, ignore this email.\n\n— FlashSale";
        String html = """
                <!doctype html><html><body style="margin:0;background:#f6f7fb;font-family:system-ui,-apple-system,Segoe UI,Roboto,Arial,sans-serif;color:#151a2d">
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0"><tr><td align="center" style="padding:24px 12px">
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="max-width:520px;background:#ffffff;border:1px solid #e2e5ef;border-radius:14px">
                <tr><td style="padding:20px 24px;border-bottom:1px solid #e2e5ef;font-weight:700;font-size:17px;color:#4f46e5">FlashSale</td></tr>
                <tr><td style="padding:24px">
                <h1 style="margin:0 0 16px;font-size:20px;line-height:1.3">%s</h1>
                <p style="margin:0 0 12px">Hi %s,</p>
                <p style="margin:0 0 20px">Confirm your email address so we can send you your tickets.</p>
                <a href="%s" style="display:inline-block;background:#4f46e5;color:#ffffff;text-decoration:none;font-weight:600;padding:10px 16px;border-radius:10px">Confirm email</a>
                <p style="margin:20px 0 0;font-size:13px;color:#5d6479">The link works for 24 hours. If you did not create a FlashSale account, ignore this email.</p>
                </td></tr></table></td></tr></table></body></html>
                """.formatted(esc(subject), esc(username), esc(link));
        return new Email(subject, text, html, null);
    }

    private Email email(String subject, String greeting, List<String> paragraphs, Event event, String date,
                        String seats, BigDecimal amount, String reference, String invite) {
        StringBuilder text = new StringBuilder(greeting).append("\n\n");
        paragraphs.forEach(p -> text.append(p).append("\n\n"));
        text.append("Event:  ").append(event.name()).append('\n')
            .append("When:   ").append(date).append('\n')
            .append("Where:  ").append(event.venue()).append('\n')
            .append("Seats:  ").append(seats).append('\n');
        if (amount != null) text.append("Amount: ").append(money.format(amount)).append('\n');
        if (reference != null) text.append("Ref:    ").append(reference).append('\n');
        text.append("\n").append(appUrl).append("/#event-").append(event.id()).append("\n\n— FlashSale");

        StringBuilder rows = new StringBuilder();
        row(rows, "Event", event.name());
        row(rows, "When", date);
        row(rows, "Where", event.venue());
        row(rows, "Seats", seats);
        if (amount != null) row(rows, "Amount", money.format(amount));
        if (reference != null) row(rows, "Reference", reference);

        StringBuilder body = new StringBuilder();
        paragraphs.forEach(p -> body.append("<p style=\"margin:0 0 12px\">").append(esc(p)).append("</p>"));

        String html = """
                <!doctype html><html><body style="margin:0;background:#f6f7fb;font-family:system-ui,-apple-system,Segoe UI,Roboto,Arial,sans-serif;color:#151a2d">
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0"><tr><td align="center" style="padding:24px 12px">
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="max-width:520px;background:#ffffff;border:1px solid #e2e5ef;border-radius:14px">
                <tr><td style="padding:20px 24px;border-bottom:1px solid #e2e5ef;font-weight:700;font-size:17px;color:#4f46e5">FlashSale</td></tr>
                <tr><td style="padding:24px">
                <h1 style="margin:0 0 16px;font-size:20px;line-height:1.3">%s</h1>
                <p style="margin:0 0 12px">%s</p>
                %s
                <table role="presentation" cellpadding="0" cellspacing="0" style="margin:16px 0;width:100%%;font-size:14px">%s</table>
                <a href="%s" style="display:inline-block;background:#4f46e5;color:#ffffff;text-decoration:none;font-weight:600;padding:10px 16px;border-radius:10px">Open FlashSale</a>
                </td></tr></table>
                <p style="font-size:12px;color:#5d6479;margin:16px 0 0">You are receiving this because you booked on FlashSale.</p>
                </td></tr></table></body></html>
                """.formatted(esc(subject), esc(greeting), body, rows, esc(appUrl + "/#event-" + event.id()));
        return new Email(subject, text.toString(), html, invite);
    }

    private static void row(StringBuilder rows, String label, String value) {
        rows.append("<tr><td style=\"padding:6px 12px 6px 0;color:#5d6479;width:90px;vertical-align:top\">")
            .append(esc(label)).append("</td><td style=\"padding:6px 0;font-weight:600\">")
            .append(esc(value)).append("</td></tr>");
    }

    static String seatList(List<String> seatNos) {
        return String.join(", ", seatNos.stream()
                .map(s -> "Row " + s.charAt(0) + " Seat " + Integer.parseInt(s.substring(1)))
                .toList());
    }

    private static String esc(String s) {
        return HtmlUtils.htmlEscape(s);
    }

    private static String orElse(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
