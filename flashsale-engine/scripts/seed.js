// Optional: the app seeds the demo event itself on first start (DataSeeder).
// Use this to rebuild the event from scratch. Users are kept. Restart the app afterwards.
// Run: mongosh "mongodb://localhost:27017/?replicaSet=rs0" scripts/seed.js
db = db.getSiblingDB('flashsale');

db.events.deleteMany({});
db.seats.deleteMany({});
db.bookings.deleteMany({});
db.payments.deleteMany({});
db.outbox.deleteMany({});

db.events.insertOne({
  _id: NumberLong(1),
  name: 'Demo Concert',
  venue: 'City Arena',
  startTime: new Date('2026-12-31T14:30:00Z'),
  totalSeats: 100,
  price: NumberDecimal('1499.00')
});

const seats = [];
for (const row of 'ABCDEFGHIJ') {
  for (let n = 1; n <= 10; n++) {
    seats.push({ eventId: NumberLong(1), seatNo: row + String(n).padStart(2, '0'), status: 'AVAILABLE', version: NumberLong(0) });
  }
}
db.seats.insertMany(seats);

print('Seeded 1 event and ' + db.seats.countDocuments() + ' seats. Also run: redis-cli FLUSHDB');
