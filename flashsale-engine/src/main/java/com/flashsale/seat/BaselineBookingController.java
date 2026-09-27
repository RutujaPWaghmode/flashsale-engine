package com.flashsale.seat;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** DB-only booking endpoints, kept for the before/after benchmark. The Redis flow comes in week 3. */
@RestController
@RequestMapping("/api/baseline/bookings")
public class BaselineBookingController {

    public record BaselineBookingRequest(long eventId, @NotBlank String seatNo) {}

    public record BaselineBookingResponse(String status, String seatNo) {}

    private final NaiveBookingService naive;
    private final AtomicDbBookingService atomic;

    public BaselineBookingController(NaiveBookingService naive, AtomicDbBookingService atomic) {
        this.naive = naive;
        this.atomic = atomic;
    }

    @PostMapping("/naive")
    public ResponseEntity<BaselineBookingResponse> naive(@Valid @RequestBody BaselineBookingRequest req) {
        return toResponse(naive.book(req.eventId(), req.seatNo()), req.seatNo());
    }

    @PostMapping("/atomic")
    public ResponseEntity<BaselineBookingResponse> atomic(@Valid @RequestBody BaselineBookingRequest req) {
        return toResponse(atomic.book(req.eventId(), req.seatNo()), req.seatNo());
    }

    private ResponseEntity<BaselineBookingResponse> toResponse(boolean booked, String seatNo) {
        return booked
                ? ResponseEntity.ok(new BaselineBookingResponse("BOOKED", seatNo))
                : ResponseEntity.status(HttpStatus.CONFLICT).body(new BaselineBookingResponse("SEAT_TAKEN", seatNo));
    }
}
