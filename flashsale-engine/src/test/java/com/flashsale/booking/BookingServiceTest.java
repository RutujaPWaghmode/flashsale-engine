package com.flashsale.booking;

import com.flashsale.common.ConflictException;
import com.flashsale.common.NotFoundException;
import com.flashsale.hold.IdempotencyStore;
import com.flashsale.hold.SeatHoldService;
import com.flashsale.infra.FlashsaleMetrics;
import com.flashsale.infra.OutboxService;
import com.flashsale.notify.SeatUpdateNotifier;
import com.flashsale.seat.Event;
import com.flashsale.seat.EventCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Hot-path decisions, without any infrastructure. */
class BookingServiceTest {

    private final EventCatalog catalog = mock(EventCatalog.class);
    private final IdempotencyStore idempotency = mock(IdempotencyStore.class);
    private final SeatHoldService seatHolds = mock(SeatHoldService.class);
    private final BookingRepository bookings = mock(BookingRepository.class);
    private final SeatUpdateNotifier notifier = mock(SeatUpdateNotifier.class);

    private BookingService service;

    @BeforeEach
    void setUp() {
        Event event = new Event(1, "Test", "Hall", Instant.now().plusSeconds(86400), 2, new BigDecimal("10.00"));
        when(catalog.find(1L)).thenReturn(Optional.of(new EventCatalog.Entry(event, List.of("A01", "A02"), Set.of("A01", "A02"))));
        service = new BookingService(catalog, idempotency, seatHolds, bookings, mock(BookingTransitions.class),
                mock(OutboxService.class), mock(TransactionTemplate.class), notifier, mock(FlashsaleMetrics.class),
                new BookingProperties(Duration.ofMinutes(5), Duration.ofMinutes(2), 4));
    }

    @Test
    void unknownSeat_isRejectedBeforeTouchingRedis() {
        assertThatThrownBy(() -> service.hold("alice", "k1", 1, "Z99")).isInstanceOf(NotFoundException.class);
        verifyNoInteractions(idempotency, seatHolds);
    }

    @Test
    void startedEvent_rejectsHolds() {
        Event past = new Event(1, "Test", "Hall", Instant.now().minusSeconds(60), 2, new BigDecimal("10.00"));
        when(catalog.find(1L)).thenReturn(Optional.of(new EventCatalog.Entry(past, List.of("A01"), Set.of("A01"))));

        assertThatThrownBy(() -> service.hold("alice", "k1", 1, "A01")).isInstanceOf(ConflictException.class);
        verifyNoInteractions(idempotency, seatHolds);
    }

    @Test
    void newHold_insertsBookingAndRecordsIdempotencyResult() {
        when(idempotency.begin(1, "alice", "k1")).thenReturn(new IdempotencyStore.Claim(IdempotencyStore.State.NEW, null));
        when(seatHolds.tryHold(1, "A01", "alice")).thenReturn(SeatHoldService.HoldResult.HELD);
        when(bookings.insert(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));

        HoldOutcome outcome = service.hold("alice", "k1", 1, "A01");

        assertThat(outcome.status()).isEqualTo(HoldOutcome.Status.HELD);
        assertThat(outcome.booking().amount()).isEqualByComparingTo("10.00");
        verify(idempotency).complete(eq(1L), eq("alice"), eq("k1"), any());
        verify(notifier).seatChanged(1, "A01");
    }

    @Test
    void seatTaken_abandonsIdempotencyKeyAndSkipsMongo() {
        when(idempotency.begin(1, "alice", "k1")).thenReturn(new IdempotencyStore.Claim(IdempotencyStore.State.NEW, null));
        when(seatHolds.tryHold(1, "A01", "alice")).thenReturn(SeatHoldService.HoldResult.SEAT_TAKEN);

        assertThat(service.hold("alice", "k1", 1, "A01").status()).isEqualTo(HoldOutcome.Status.SEAT_TAKEN);
        verify(idempotency).abandon(1, "alice", "k1");
        verifyNoInteractions(bookings);
    }

    @Test
    void holdLimit_isReported() {
        when(idempotency.begin(1, "alice", "k1")).thenReturn(new IdempotencyStore.Claim(IdempotencyStore.State.NEW, null));
        when(seatHolds.tryHold(1, "A01", "alice")).thenReturn(SeatHoldService.HoldResult.HOLD_LIMIT);

        assertThat(service.hold("alice", "k1", 1, "A01").status()).isEqualTo(HoldOutcome.Status.HOLD_LIMIT);
    }

    @Test
    void repeatedKey_returnsOriginalBookingWithoutNewHold() {
        Booking prior = Booking.held("alice", "alice:1:k1", 1, "A02", BigDecimal.TEN, Instant.now());
        when(idempotency.begin(1, "alice", "k1")).thenReturn(new IdempotencyStore.Claim(IdempotencyStore.State.DONE, "b1"));
        when(bookings.findById("b1")).thenReturn(Optional.of(prior));

        HoldOutcome outcome = service.hold("alice", "k1", 1, "A01");

        assertThat(outcome.status()).isEqualTo(HoldOutcome.Status.DUPLICATE);
        assertThat(outcome.seatNo()).isEqualTo("A02");
        verifyNoInteractions(seatHolds);
    }

    @Test
    void keyStillProcessing_isReportedAsInProgress() {
        when(idempotency.begin(1, "alice", "k1")).thenReturn(new IdempotencyStore.Claim(IdempotencyStore.State.IN_PROGRESS, null));

        assertThat(service.hold("alice", "k1", 1, "A01").status()).isEqualTo(HoldOutcome.Status.IN_PROGRESS);
        verifyNoInteractions(seatHolds);
    }
}
