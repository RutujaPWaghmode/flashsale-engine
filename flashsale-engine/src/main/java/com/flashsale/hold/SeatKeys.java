package com.flashsale.hold;

/** Redis key names. The {evtN} hash tag keeps all keys of one event in the same Redis Cluster slot. */
public final class SeatKeys {

    private SeatKeys() {}

    public static String seat(long eventId, String seatNo) {
        return "seat:{evt" + eventId + "}:" + seatNo;
    }

    public static String userHolds(long eventId, String userId) {
        return "user-holds:{evt" + eventId + "}:" + userId;
    }

    public static String eventPattern(long eventId) {
        return "*:{evt" + eventId + "}:*";
    }
}
