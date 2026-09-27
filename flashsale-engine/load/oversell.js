// Oversell test: USERS users hit the SAME seat at the SAME instant, once per round.
// Each round uses a fresh seat (A01, A02, ...), so a correct booking method has exactly one winner per round.
// More winners than rounds means a seat was sold to more than one person.
//
// The throughput benchmark (booking-load.js) cannot show this: it spreads requests over 100 random seats and
// starts slowly, so two requests almost never race for the same seat. Here every user waits for a shared
// start time and fires together.
//
// Run through scripts/oversell.sh, or directly:
//   ~/tools/k6/k6 run -e MODE=naive -e ADMIN_PASSWORD=... load/oversell.js
import http from 'k6/http';
import { sleep } from 'k6';
import { Counter } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const MODE = __ENV.MODE || 'naive';
const USERS = parseInt(__ENV.USERS || '100', 10);
const ROUNDS = Math.min(parseInt(__ENV.ROUNDS || '10', 10), 100);
const GAP_MS = parseInt(__ENV.GAP_MS || '1000', 10);      // time between rounds
const PATHS = {
  redis: '/api/bookings',
  atomic: '/api/baseline/bookings/atomic',
  naive: '/api/baseline/bookings/naive',
};
const ROWS = 'ABCDEFGHIJ';
const seatFor = (round) => ROWS[Math.floor(round / 10)] + String((round % 10) + 1).padStart(2, '0');
const SEATS = Array.from({ length: ROUNDS }, (_, r) => seatFor(r));

const wins = new Counter('race_wins');       // 200: this user got the seat
const losses = new Counter('race_losses');   // 409: seat already taken
const errors = new Counter('race_errors');   // anything else

http.setResponseCallback(http.expectedStatuses(200, 201, 204, 409));   // 201/204 come from setup()

// A no-op threshold per seat makes k6 keep a win count for each seat, which handleSummary prints.
const perSeat = {};
for (const s of SEATS) perSeat[`race_wins{seat:${s}}`] = ['count>=0'];

export const options = {
  setupTimeout: '120s',
  scenarios: {
    race: {
      executor: 'per-vu-iterations',
      vus: USERS,
      iterations: ROUNDS,
      maxDuration: `${Math.ceil((ROUNDS * GAP_MS) / 1000) + 60}s`,
    },
  },
  thresholds: perSeat,
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
  return { tokens, start: Date.now() + 3000 };   // 3 s for every VU to get ready before round 1
}

export default function (data) {
  const round = __ITER;
  const seatNo = seatFor(round);
  const wait = data.start + round * GAP_MS - Date.now();
  if (wait > 0) sleep(wait / 1000);   // every VU wakes at the same moment

  const token = data.tokens[(__VU - 1) % data.tokens.length];
  const headers = {
    'Content-Type': 'application/json',
    Authorization: `Bearer ${token}`,
    'Idempotency-Key': `race-${__VU}-${round}-${data.start}`,
  };
  const res = http.post(`${BASE}${PATHS[MODE]}`, JSON.stringify({ eventId: 1, seatNo }), { headers, tags: { seat: seatNo } });

  if (res.status === 200) wins.add(1, { seat: seatNo });
  else if (res.status === 409) losses.add(1);
  else errors.add(1);
}

export function handleSummary(data) {
  const count = (m) => (data.metrics[m] ? data.metrics[m].values.count : 0);
  const total = count('race_wins');
  const oversold = SEATS.map((s) => [s, count(`race_wins{seat:${s}}`)]).filter(([, n]) => n > 1);

  const lines = [
    `MODE=${MODE}: ${USERS} users x ${ROUNDS} rounds (one seat per round, all users at once)`,
    `  winners: ${total} (a correct method gives exactly ${ROUNDS})`,
    `  lost (409): ${count('race_losses')}   errors: ${count('race_errors')}`,
  ];
  if (oversold.length === 0) {
    lines.push('  RESULT: no seat was sold twice');
  } else {
    lines.push(`  RESULT: OVERSOLD ${oversold.length} of ${ROUNDS} seats`);
    lines.push('  ' + oversold.map(([s, n]) => `${s} sold ${n}x`).join(', '));
  }
  return { stdout: lines.join('\n') + '\n' };
}
