package com.flashsale.seat;

import com.flashsale.security.User;
import com.flashsale.security.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * First start: the demo event (id 1, 10 x 10 seats, used by tests and k6), a few more events on
 * upcoming dates for the calendar, and the admin user. Runs after index creation.
 */
@Component
@Order(1)
public class DataSeeder implements ApplicationRunner {

    public static final long DEMO_EVENT_ID = 1;
    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);
    private static final ZoneId LOCAL = ZoneId.of("Asia/Kolkata");

    private record Seed(long id, String name, String venue, Instant start, int rows, int seatsPerRow, String price) {}

    private final EventRepository events;
    private final MongoTemplate mongo;
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final EventIdSequence ids;
    private final String adminUsername;
    private final String adminPassword;

    public DataSeeder(EventRepository events, MongoTemplate mongo, UserRepository users, PasswordEncoder passwordEncoder,
                      EventIdSequence ids,
                      @Value("${app.admin.username:admin}") String adminUsername,
                      @Value("${app.admin.password:}") String adminPassword) {
        this.events = events;
        this.mongo = mongo;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.ids = ids;
        this.adminUsername = adminUsername;
        this.adminPassword = adminPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<Seed> seeds = List.of(
                new Seed(DEMO_EVENT_ID, "Demo Concert", "City Arena", Instant.parse("2026-12-31T14:30:00Z"), 10, 10, "1499.00"),
                new Seed(2, "Stand-up Comedy Night", "The Laugh Store", eveningIn(10), 6, 8, "799.00"),
                new Seed(3, "Indie Film Premiere", "Regal Screens", eveningIn(21), 8, 12, "499.00"),
                new Seed(4, "Jazz by the Lake", "Lakeside Amphitheatre", eveningIn(35), 5, 10, "999.00"));
        for (Seed s : seeds) {
            if (!events.existsById(s.id())) {
                create(new Event(s.id(), s.name(), s.venue(), s.start(), s.rows() * s.seatsPerRow(), new BigDecimal(s.price())),
                        s.rows(), s.seatsPerRow());
                log.info("Seeded event {} '{}' with {} seats", s.id(), s.name(), s.rows() * s.seatsPerRow());
            }
        }
        events.findAll().stream().mapToLong(Event::id).max().ifPresent(ids::ensureAtLeast);

        if (!adminPassword.isBlank() && users.findByUsername(adminUsername).isEmpty()) {
            users.insert(new User(null, adminUsername, passwordEncoder.encode(adminPassword), List.of("USER", "ADMIN"), null, false));
            log.info("Created admin user '{}'", adminUsername);
        }
    }

    /** Inserts the event and its seat documents. Also used by admin event creation (inside a transaction). */
    public void create(Event event, int rows, int seatsPerRow) {
        events.insert(event);
        mongo.insert(seatDocuments(event.id(), rows, seatsPerRow), "seats");
    }

    public static List<org.bson.Document> seatDocuments(long eventId, int rows, int seatsPerRow) {
        List<org.bson.Document> docs = new ArrayList<>();
        for (int r = 0; r < rows; r++) {
            char row = (char) ('A' + r);
            for (int n = 1; n <= seatsPerRow; n++) {
                docs.add(new org.bson.Document("eventId", eventId)
                        .append("seatNo", row + String.format("%02d", n))
                        .append("status", SeatStatus.AVAILABLE.name())
                        .append("version", 0L));
            }
        }
        return docs;
    }

    private static Instant eveningIn(int days) {
        return LocalDate.now(LOCAL).plusDays(days).atTime(LocalTime.of(20, 0)).atZone(LOCAL).toInstant();
    }
}
