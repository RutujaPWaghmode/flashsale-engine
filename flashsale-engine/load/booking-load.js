// Flash-sale load test: many users fire at 100 seats at once.
//
// Start the app with the loadtest profile first (it lifts the per-IP sign-up limit, since k6 creates
// hundreds of users from one machine):   ./mvnw spring-boot:run -Dspring-boot.run.profiles=loadtest
//
//   ~/tools/k6/k6 run -e MODE=redis  -e ADMIN_PASSWORD=... load/booking-load.js
//   ~/tools/k6/k6 run -e MODE=atomic -e ADMIN_PASSWORD=... load/booking-load.js
//   ~/tools/k6/k6 run -e MODE=naive  -e ADMIN_PASSWORD=... load/booking-load.js
//
// MODE=redis  -> POST /api/bookings                 (Redis Lua hold, the real flow)
// MODE=atomic -> POST /api/baseline/bookings/atomic (MongoDB conditional update, the "before" number)
// MODE=naive  -> POST /api/baseline/bookings/naive  (racy read-then-write, shows overselling)
//
// setup() resets the event through the admin API and logs in USERS test users, so every run starts clean.
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const MODE = __ENV.MODE || 'redis';
const USERS = parseInt(__ENV.USERS || '300', 10);
const RATE = parseInt(__ENV.RATE || '1000', 10);          // target requests per second
const RAMP = __ENV.RAMP || '15s';
const HOLD = __ENV.HOLD || '45s';
const PATHS = {
  redis: '/api/bookings',
  atomic: '/api/baseline/bookings/atomic',
  naive: '/api/baseline/bookings/naive',
};
const ROWS = 'ABCDEFGHIJ';

const won = new Counter('seats_won');           // 200 responses: every one is a sold/held seat
const taken = new Counter('seats_taken');       // 409 responses
const limited = new Counter('rate_limited');    // 429 responses

http.setResponseCallback(http.expectedStatuses(200, 201, 204, 409));   // 201/204 come from setup()

export const options = {
  setupTimeout: '180s',
  scenarios: {
    flash_sale: {
      executor: 'ramping-arrival-rate',
      startRate: 50,
      timeUnit: '1s',
      preAllocatedVUs: 200,
      maxVUs: 1000,
      stages: [
        { target: RATE, duration: RAMP },
        { target: RATE, duration: HOLD },
      ],
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{expected_response:true}': ['p(95)<500'],
  },
};

const json = { headers: { 'Content-Type': 'application/json' } };

export function setup() {
  if (!PATHS[MODE]) throw new Error(`Unknown MODE ${MODE}`);
  if (!__ENV.ADMIN_PASSWORD) throw new Error('Pass -e ADMIN_PASSWORD=... (see .env)');

  const admin = http.post(`${BASE}/api/auth/login`, JSON.stringify({ username: 'admin', password: __ENV.ADMIN_PASSWORD }), json);
  if (admin.status !== 200) throw new Error(`Admin login failed: ${admin.status}`);
  const reset = http.post(`${BASE}/api/admin/events/1/reset`, null, { headers: { Authorization: `Bearer ${admin.json('token')}` } });
  if (reset.status !== 204) throw new Error(`Reset failed: ${reset.status}`);

  const tokens = [];
  for (let start = 0; start < USERS; start += 50) {
    const names = [];
    for (let i = start; i < Math.min(start + 50, USERS); i++) names.push(`load-${i}`);
    const creds = names.map((u) => JSON.stringify({ username: u, password: 'LoadTest-pw1' }));
    http.batch(creds.map((body) => ['POST', `${BASE}/api/auth/register`, body, json]));   // 201 or 409, both fine
    const logins = http.batch(creds.map((body) => ['POST', `${BASE}/api/auth/login`, body, json]));
    for (const r of logins) if (r.status === 200) tokens.push(r.json('token'));
  }
  if (tokens.length === 0) throw new Error('No test users could log in');
  console.log(`MODE=${MODE}: ${tokens.length} users ready`);
  return { tokens };
}

export default function (data) {
  // Random user per request spreads load so the per-user rate limit is not what we measure.
  const token = data.tokens[Math.floor(Math.random() * data.tokens.length)];
  const seatNo = ROWS[Math.floor(Math.random() * 10)] + String(1 + Math.floor(Math.random() * 10)).padStart(2, '0');
  const headers = {
    'Content-Type': 'application/json',
    Authorization: `Bearer ${token}`,
    'Idempotency-Key': `${__VU}-${__ITER}-${Date.now()}`,
  };
  const res = http.post(`${BASE}${PATHS[MODE]}`, JSON.stringify({ eventId: 1, seatNo }), { headers });

  if (res.status === 200) won.add(1);
  else if (res.status === 409) taken.add(1);
  else if (res.status === 429) limited.add(1);
  check(res, { 'status is 200 or 409': (r) => r.status === 200 || r.status === 409 });
}

export function teardown() {
  const event = http.get(`${BASE}/api/events/1`).json();
  console.log(`MODE=${MODE}: seats now ${JSON.stringify(event.seats)} (100 seats total).`);
  console.log('seats_won above 100 means the same seat was sold more than once.');
}
