package com.flashsale.booking;

import com.flashsale.payment.PaymentOutcome;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** The user id always comes from the JWT, never from the request body. */
@RestController
@RequestMapping("/api/bookings")
@Validated
public class BookingController {

    public record HoldRequest(long eventId, @NotBlank @Size(max = 10) String seatNo) {}

    public record PayRequest(PaymentOutcome outcome) {}

    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @PostMapping
    public ResponseEntity<HoldOutcome> hold(@AuthenticationPrincipal Jwt jwt,
                                            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 100) String idempotencyKey,
                                            @Valid @RequestBody HoldRequest request) {
        HoldOutcome outcome = bookingService.hold(jwt.getSubject(), idempotencyKey, request.eventId(), request.seatNo());
        return ResponseEntity.status(outcome.succeeded() ? HttpStatus.OK : HttpStatus.CONFLICT).body(outcome);
    }

    @GetMapping
    public List<BookingView> mine(@AuthenticationPrincipal Jwt jwt, @RequestParam long eventId) {
        return bookingService.mine(jwt.getSubject(), eventId);
    }

    @GetMapping("/{bookingId}")
    public BookingView get(@AuthenticationPrincipal Jwt jwt, @PathVariable String bookingId) {
        return bookingService.get(jwt.getSubject(), bookingId);
    }

    @PostMapping("/{bookingId}/pay")
    public ResponseEntity<BookingView> pay(@AuthenticationPrincipal Jwt jwt, @PathVariable String bookingId,
                                           @RequestBody(required = false) PayRequest request) {
        BookingView view = bookingService.pay(jwt.getSubject(), bookingId, request != null ? request.outcome() : null);
        return ResponseEntity.accepted().body(view);
    }

    @PostMapping("/{bookingId}/cancel")
    public BookingView cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable String bookingId) {
        return bookingService.cancel(jwt.getSubject(), bookingId);
    }
}
