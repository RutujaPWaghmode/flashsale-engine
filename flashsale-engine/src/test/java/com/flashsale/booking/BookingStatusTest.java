package com.flashsale.booking;

import org.junit.jupiter.api.Test;

import static com.flashsale.booking.BookingStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

class BookingStatusTest {

    @Test
    void happyPath() {
        assertThat(HELD.canTransitionTo(PAYMENT_PENDING)).isTrue();
        assertThat(PAYMENT_PENDING.canTransitionTo(CONFIRMED)).isTrue();
    }

    @Test
    void compensationPaths() {
        assertThat(PAYMENT_PENDING.canTransitionTo(FAILED)).isTrue();
        assertThat(PAYMENT_PENDING.canTransitionTo(REFUNDED)).isTrue();
        assertThat(EXPIRED.canTransitionTo(REFUNDED)).isTrue();
        assertThat(HELD.canTransitionTo(CANCELLED)).isTrue();
    }

    @Test
    void illegalTransitions() {
        assertThat(HELD.canTransitionTo(CONFIRMED)).isFalse();        // must pay first
        assertThat(EXPIRED.canTransitionTo(CONFIRMED)).isFalse();     // late payment is refunded, not honoured
        assertThat(CONFIRMED.canTransitionTo(FAILED)).isFalse();
        assertThat(CANCELLED.canTransitionTo(HELD)).isFalse();
    }

    @Test
    void finalStatesAreTerminal() {
        for (BookingStatus end : new BookingStatus[]{CONFIRMED, FAILED, CANCELLED, REFUNDED}) {
            assertThat(end.isFinal()).isTrue();
            for (BookingStatus next : values()) {
                assertThat(end.canTransitionTo(next)).as(end + " -> " + next).isFalse();
            }
        }
    }
}
