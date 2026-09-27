package com.flashsale.notify;

import com.flashsale.seat.SeatStatus;

/** Pushed to browsers. type = SEAT (one seat changed) or RESET (reload everything). */
public record SeatUpdate(String type, long eventId, String seatNo, SeatStatus status) {

    public static SeatUpdate seat(long eventId, String seatNo, SeatStatus status) {
        return new SeatUpdate("SEAT", eventId, seatNo, status);
    }

    public static SeatUpdate reset(long eventId) {
        return new SeatUpdate("RESET", eventId, null, null);
    }
}
