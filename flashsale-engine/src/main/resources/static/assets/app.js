// FlashSale UI. Plain ES module, no build step. Served by the Spring Boot app itself.

const LEGACY_TOKEN_KEY = 'flashsale.token';   // older versions stored the JWT here; removed on start
const ACTIVE = new Set(['HELD', 'PAYMENT_PENDING']);
const STATUS_TEXT = {
  HELD: 'On hold',
  PAYMENT_PENDING: 'Paying…',
  CONFIRMED: 'Confirmed',
  FAILED: 'Payment declined',
  EXPIRED: 'Hold expired',
  CANCELLED: 'Released',
  REFUNDED: 'Refunded',
};

const state = {
  eventId: null,
  event: null,
  events: [],              // calendar feed, sorted by start time
  calMonth: null,          // Date at the 1st of the month shown
  seatsEventId: null,      // event the seat map was built for
  seats: new Map(),        // seatNo -> AVAILABLE | HELD | BOOKED
  mine: [],                // my bookings, newest first
  token: null,
  user: null,              // { name, admin, exp }
  pendingSeats: new Set(), // hold requests in flight
  pendingSeatAfterLogin: null,
  showHistory: false,
  socket: null,
  socketRetry: 0,
  reconnectTimer: null,
};

const $ = (id) => document.getElementById(id);
const money = (amount) => new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR' }).format(amount);

// ---------- session ----------
// The JWT lives in an httpOnly cookie that this script cannot read (so injected scripts cannot steal
// it either). Who is signed in comes from /api/auth/me.

function setUser(me) {
  state.user = me ? { name: me.username, admin: me.admin, email: me.email, emailVerified: me.emailVerified } : null;
  renderAuth();
}

async function loadMe() {
  try {
    const { data } = await api('/api/auth/me');
    setUser(data);
  } catch {
    setUser(null);   // 401: not signed in
  }
}

async function signOut() {
  try { await api('/api/auth/logout', { method: 'POST' }); } catch { /* cookie cleared anyway on next 401 */ }
  setUser(null);
  await loadMine();
}

// ---------- HTTP ----------

class ApiError extends Error {
  constructor(status, body) {
    super(body?.detail || body?.title || `HTTP ${status}`);
    this.status = status;
    this.body = body;
  }
}

async function api(path, { method = 'GET', body, headers = {} } = {}) {
  // The custom header is the CSRF guard: the server only accepts the cookie on writes when it is present.
  const init = { method, credentials: 'same-origin', headers: { 'X-Requested-With': 'FlashSale', ...headers } };
  if (body !== undefined) {
    init.headers['Content-Type'] = 'application/json';
    init.body = JSON.stringify(body);
  }
  const res = await fetch(path, init);
  const text = await res.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch { data = { detail: text }; }
  if (res.status === 401 && state.user && path !== '/api/auth/login') {
    setUser(null);
    toast('Your session expired. Please sign in again.', 'error');
  }
  // A 409 from POST /api/bookings is a normal outcome (SEAT_TAKEN...), not an error.
  if (!res.ok && !(res.status === 409 && typeof data?.status === 'string')) throw new ApiError(res.status, data);
  return { status: res.status, data };
}

function uuid() {
  if (crypto.randomUUID) return crypto.randomUUID();
  return 'k' + Date.now().toString(36) + Math.random().toString(36).slice(2);
}

// ---------- loading ----------

async function loadEvents() {
  const { data } = await api('/api/events');
  state.events = data;
  renderCalendar();
  renderUpcoming();
}

async function loadEvent() {
  const { data } = await api(`/api/events/${state.eventId}`);
  state.event = data;
  $('event-ended').hidden = data.onSale;
  $('event-name').textContent = data.name;
  $('event-meta').textContent = `${data.venue} · ${new Date(data.startTime).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' })}`;
  $('event-price').textContent = money(data.price);
  if (data.maxSeatsPerUser) $('max-seats').textContent = data.maxSeatsPerUser;
  document.title = `${data.name} · FlashSale`;
}

async function loadSeats() {
  const eventId = state.eventId;
  const { data } = await api(`/api/events/${eventId}/seats`);
  if (eventId !== state.eventId) return;   // user switched events meanwhile
  state.seats = new Map(data.map((s) => [s.seatNo, s.status]));
  if (state.seatsEventId !== eventId) {
    state.seatsEventId = eventId;
    buildSeatMap();
  }
  renderSeats();
}

async function loadMine() {
  if (!state.user) {
    state.mine = [];
    renderMine();
    renderSeats();
    return;
  }
  const { data } = await api(`/api/bookings?eventId=${state.eventId}`);
  const before = new Map(state.mine.map((b) => [b.id, b.status]));
  state.mine = data;
  for (const b of data) {
    const prev = before.get(b.id);
    if (prev && prev !== b.status) announce(b);
  }
  renderMine();
  renderSeats();
}

function announce(b) {
  const seat = seatLabel(b.seatNo);
  if (b.status === 'CONFIRMED') toast(`Seat ${seat} is yours. Enjoy the show!`, 'success');
  else if (b.status === 'FAILED') toast(`Payment for ${seat} was declined. The seat was released.`, 'error');
  else if (b.status === 'REFUNDED') toast(`${seat}: ${b.failureReason || 'payment refunded'}.`, 'error');
  else if (b.status === 'EXPIRED') toast(`Your hold on ${seat} expired.`, 'error');
}

// ---------- seat map ----------

function seatLabel(seatNo) {
  return seatNo[0] + Number(seatNo.slice(1));
}

function buildSeatMap() {
  const map = $('seatmap');
  map.textContent = '';
  const rows = new Map();
  for (const seatNo of state.seats.keys()) {
    const row = seatNo[0];
    if (!rows.has(row)) rows.set(row, []);
    rows.get(row).push(seatNo);
  }
  for (const [row, seatNos] of rows) {
    const rowEl = document.createElement('div');
    rowEl.className = 'seat-row';
    rowEl.setAttribute('role', 'row');
    rowEl.append(rowLabel(row));
    seatNos.forEach((seatNo, i) => {
      if (i === Math.ceil(seatNos.length / 2)) {
        const aisle = document.createElement('span');
        aisle.className = 'aisle';
        rowEl.append(aisle);
      }
      const btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'seat';
      btn.dataset.seat = seatNo;
      btn.setAttribute('role', 'gridcell');
      btn.textContent = Number(seatNo.slice(1));
      btn.addEventListener('click', () => onSeatClick(seatNo));
      rowEl.append(btn);
    });
    rowEl.append(rowLabel(row));
    map.append(rowEl);
  }
}

function rowLabel(row) {
  const el = document.createElement('span');
  el.className = 'row-label';
  el.textContent = row;
  el.setAttribute('aria-hidden', 'true');
  return el;
}

function mySeatStatus(seatNo) {
  const b = state.mine.find((x) => x.seatNo === seatNo && (ACTIVE.has(x.status) || x.status === 'CONFIRMED'));
  if (!b) return null;
  return b.status === 'CONFIRMED' ? 'mine-sold' : 'mine';
}

function renderSeats() {
  let available = 0, held = 0, booked = 0;
  for (const [seatNo, status] of state.seats) {
    if (status === 'AVAILABLE') available++;
    else if (status === 'HELD') held++;
    else booked++;
    const btn = document.querySelector(`.seat[data-seat="${seatNo}"]`);
    if (!btn) continue;
    const mine = mySeatStatus(seatNo);
    const cls = mine || (status === 'AVAILABLE' ? 'available' : status === 'HELD' ? 'held' : 'sold');
    btn.className = `seat ${cls}${state.pendingSeats.has(seatNo) ? ' pending' : ''}`;
    btn.disabled = cls === 'held' || cls === 'sold' || cls === 'mine-sold' || (cls === 'available' && state.event && !state.event.onSale);
    const words = { available: 'available', held: 'on hold', sold: 'sold', mine: 'held by you', 'mine-sold': 'booked by you' };
    btn.setAttribute('aria-label', `Row ${seatNo[0]} seat ${Number(seatNo.slice(1))}, ${words[cls]}`);
  }
  const total = state.seats.size || 1;
  $('stat-available').textContent = available;
  $('stat-held').textContent = held;
  $('stat-booked').textContent = booked;
  $('progress-sold').style.width = `${(booked / total) * 100}%`;
  $('progress-held').style.width = `${(held / total) * 100}%`;
  $('progress').setAttribute('aria-valuenow', Math.round((booked / total) * 100));
}

function flashSeat(seatNo) {
  const btn = document.querySelector(`.seat[data-seat="${seatNo}"]`);
  if (!btn) return;
  btn.classList.remove('flash');
  void btn.offsetWidth;
  btn.classList.add('flash');
}

// ---------- actions ----------

async function onSeatClick(seatNo) {
  if (mySeatStatus(seatNo) === 'mine') {
    highlightBooking(seatNo);
    return;
  }
  if (!state.user) {
    state.pendingSeatAfterLogin = seatNo;
    openAuth('login');
    return;
  }
  await holdSeat(seatNo);
}

async function holdSeat(seatNo) {
  if (state.pendingSeats.has(seatNo)) return;
  state.pendingSeats.add(seatNo);
  renderSeats();
  const key = uuid();
  const request = () => api('/api/bookings', {
    method: 'POST',
    headers: { 'Idempotency-Key': key },
    body: { eventId: state.eventId, seatNo },
  });
  try {
    let res;
    try {
      res = await request();
    } catch (e) {
      if (e instanceof ApiError) throw e;
      res = await request();   // network blip: retry with the SAME key, the server dedupes it
    }
    const outcome = res.data;
    if (outcome.status === 'HELD' || outcome.status === 'DUPLICATE') {
      state.seats.set(seatNo, 'HELD');
      toast(`Seat ${seatLabel(seatNo)} is held for you. Pay within ${holdMinutes()} minutes.`);
    } else if (outcome.status === 'SEAT_TAKEN') {
      toast(`Too slow! Someone else grabbed ${seatLabel(seatNo)}.`, 'error');
      state.seats.set(seatNo, 'HELD');
    } else if (outcome.status === 'HOLD_LIMIT') {
      toast(`You can hold at most ${state.event?.maxSeatsPerUser ?? 4} seats.`, 'error');
    } else {
      toast('That request is still being processed.', 'error');
    }
  } catch (e) {
    toast(e.status === 429 ? 'Slow down a little, too many requests.'
      : e.body?.code === 'EVENT_STARTED' ? 'Sales for this event have closed.'
      : `Could not hold the seat: ${e.message}`, 'error');
  } finally {
    state.pendingSeats.delete(seatNo);
    await loadMine().catch(() => {});
    renderSeats();
  }
}

async function pay(bookingId, outcome, button) {
  button.disabled = true;
  try {
    await api(`/api/bookings/${bookingId}/pay`, { method: 'POST', body: { outcome } });
    await loadMine();
  } catch (e) {
    toast(e.body?.code === 'HOLD_EXPIRED' ? 'Too late, your hold expired.' : `Payment failed to start: ${e.message}`, 'error');
    await Promise.all([loadMine(), loadSeats()]).catch(() => {});
  }
}

async function release(bookingId, button) {
  button.disabled = true;
  try {
    await api(`/api/bookings/${bookingId}/cancel`, { method: 'POST' });
    await Promise.all([loadMine(), loadSeats()]);
  } catch (e) {
    toast(`Could not release: ${e.message}`, 'error');
    button.disabled = false;
  }
}

function holdMinutes() {
  return state.event?.holdMinutes ?? 5;
}

function highlightBooking(seatNo) {
  const el = document.querySelector(`.booking[data-seat="${seatNo}"]`);
  if (!el) return;
  el.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
  el.classList.add('highlight');
  setTimeout(() => el.classList.remove('highlight'), 1500);
}

// ---------- your seats panel ----------

function renderMine() {
  const root = $('mine');
  root.textContent = '';
  if (!state.user) {
    root.append(emptyText('Sign in and pick a seat to start.'));
    return;
  }
  const active = state.mine.filter((b) => ACTIVE.has(b.status));
  const confirmed = state.mine.filter((b) => b.status === 'CONFIRMED');
  const past = state.mine.filter((b) => !ACTIVE.has(b.status) && b.status !== 'CONFIRMED');

  if (!active.length && !confirmed.length) root.append(emptyText('No seats yet. Pick one on the map.'));
  for (const b of [...active, ...confirmed]) root.append(bookingCard(b));

  if (past.length) {
    const toggle = document.createElement('button');
    toggle.type = 'button';
    toggle.className = 'btn ghost small history-toggle';
    toggle.textContent = state.showHistory ? 'Hide history' : `Show history (${past.length})`;
    toggle.addEventListener('click', () => { state.showHistory = !state.showHistory; renderMine(); });
    root.append(toggle);
    if (state.showHistory) for (const b of past) root.append(bookingCard(b));
  }
  tickCountdowns();
}

function emptyText(text) {
  const p = document.createElement('p');
  p.className = 'empty';
  p.textContent = text;
  return p;
}

function bookingCard(b) {
  const card = document.createElement('div');
  card.className = 'booking';
  card.dataset.seat = b.seatNo;

  const top = document.createElement('div');
  top.className = 'booking-top';
  const title = document.createElement('div');
  const seat = document.createElement('div');
  seat.className = 'booking-seat';
  seat.textContent = `Row ${b.seatNo[0]} · Seat ${Number(b.seatNo.slice(1))}`;
  const meta = document.createElement('div');
  meta.className = 'booking-meta';
  meta.textContent = money(b.amount);
  title.append(seat, meta);
  const chip = document.createElement('span');
  chip.className = `chip ${b.status}`;
  chip.textContent = STATUS_TEXT[b.status] || b.status;
  top.append(title, chip);
  card.append(top);

  if (b.status === 'HELD' || b.status === 'PAYMENT_PENDING') {
    const line = document.createElement('div');
    line.className = 'booking-meta';
    if (b.status === 'PAYMENT_PENDING') {
      const spin = document.createElement('span');
      spin.className = 'spinner';
      line.append(spin, ' Waiting for the bank · ');
    } else {
      line.append('Hold ends in ');
    }
    const cd = document.createElement('span');
    cd.className = 'countdown';
    cd.dataset.until = b.holdExpiresAt;
    line.append(cd);
    card.append(line);
  }

  if (b.status === 'HELD') {
    const actions = document.createElement('div');
    actions.className = 'booking-actions';
    const select = document.createElement('select');
    select.setAttribute('aria-label', 'Mock payment result');
    for (const [value, label] of [['SUCCESS', 'Card approves'], ['DECLINE', 'Card declines'], ['SLOW', 'Slow bank (8 s)'], ['RANDOM', 'Random (70% approve)']]) {
      const opt = document.createElement('option');
      opt.value = value;
      opt.textContent = label;
      select.append(opt);
    }
    const payBtn = document.createElement('button');
    payBtn.type = 'button';
    payBtn.className = 'btn primary';
    payBtn.textContent = `Pay ${money(b.amount)}`;
    payBtn.addEventListener('click', () => pay(b.id, select.value, payBtn));
    const releaseBtn = document.createElement('button');
    releaseBtn.type = 'button';
    releaseBtn.className = 'btn ghost small';
    releaseBtn.textContent = 'Release';
    releaseBtn.addEventListener('click', () => release(b.id, releaseBtn));
    actions.append(select, payBtn, releaseBtn);
    card.append(actions);
  }

  if (b.failureReason && (b.status === 'FAILED' || b.status === 'REFUNDED')) {
    const reason = document.createElement('div');
    reason.className = 'booking-meta';
    reason.textContent = b.failureReason;
    card.append(reason);
  }
  return card;
}

function tickCountdowns() {
  let expiredOne = false;
  for (const el of document.querySelectorAll('.countdown')) {
    const ms = new Date(el.dataset.until).getTime() - Date.now();
    const s = Math.max(0, Math.ceil(ms / 1000));
    el.textContent = `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
    el.classList.toggle('urgent', s <= 30);
    if (s === 0 && !el.dataset.done) {
      el.dataset.done = '1';
      expiredOne = true;
    }
  }
  if (expiredOne) setTimeout(() => refreshAll(), 1500);
}


// ---------- calendar ----------

const MONTH_FMT = new Intl.DateTimeFormat(undefined, { month: 'long', year: 'numeric' });
const TIME_FMT = new Intl.DateTimeFormat(undefined, { hour: 'numeric', minute: '2-digit' });
const DAY_KEY = (d) => `${d.getFullYear()}-${d.getMonth()}-${d.getDate()}`;

function renderCalendar() {
  if (!state.calMonth) return;
  const month = state.calMonth;
  $('cal-month').textContent = MONTH_FMT.format(month);

  const byDay = new Map();
  for (const ev of state.events) {
    const key = DAY_KEY(new Date(ev.startTime));
    if (!byDay.has(key)) byDay.set(key, []);
    byDay.get(key).push(ev);
  }

  const grid = $('cal-grid');
  grid.textContent = '';
  const first = new Date(month.getFullYear(), month.getMonth(), 1);
  const offset = (first.getDay() + 6) % 7;             // Monday-first
  const start = new Date(first);
  start.setDate(first.getDate() - offset);
  const todayKey = DAY_KEY(new Date());

  for (let i = 0; i < 42; i++) {
    const day = new Date(start);
    day.setDate(start.getDate() + i);
    if (i >= 35 && day.getMonth() !== month.getMonth()) break;   // drop an empty 6th week
    const cell = document.createElement('div');
    cell.className = 'cal-day';
    if (day.getMonth() !== month.getMonth()) cell.classList.add('outside');
    if (DAY_KEY(day) === todayKey) cell.classList.add('today');

    const num = document.createElement('span');
    num.className = 'cal-num';
    num.textContent = day.getDate();
    cell.append(num);

    const evs = byDay.get(DAY_KEY(day)) || [];
    if (evs.length) cell.classList.add('has-events');
    for (const ev of evs) {
      const chip = document.createElement('button');
      chip.type = 'button';
      chip.className = 'cal-event';
      if (ev.id === state.eventId) chip.classList.add('selected');
      if (!ev.onSale) chip.classList.add('past');
      chip.title = `${ev.name} · ${TIME_FMT.format(new Date(ev.startTime))}`;
      chip.setAttribute('aria-label', `${ev.name}, ${new Date(ev.startTime).toLocaleString()}`);
      const time = document.createElement('span');
      time.className = 'cal-event-time';
      time.textContent = TIME_FMT.format(new Date(ev.startTime));
      const name = document.createElement('span');
      name.className = 'cal-event-name';
      name.textContent = ev.name;
      chip.append(time, name);
      chip.addEventListener('click', () => selectEvent(ev.id));
      cell.append(chip);
    }
    grid.append(cell);
  }
}

function renderUpcoming() {
  const list = $('upcoming');
  list.textContent = '';
  const upcoming = state.events.filter((e) => e.onSale);
  if (!upcoming.length) {
    const li = document.createElement('li');
    li.className = 'empty';
    li.textContent = 'No upcoming events.';
    list.append(li);
    return;
  }
  for (const ev of upcoming) {
    const date = new Date(ev.startTime);
    const li = document.createElement('li');
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'upcoming-item';
    if (ev.id === state.eventId) btn.classList.add('selected');
    btn.addEventListener('click', () => selectEvent(ev.id));

    const badge = document.createElement('span');
    badge.className = 'date-badge';
    const mon = document.createElement('span');
    mon.className = 'date-mon';
    mon.textContent = date.toLocaleString(undefined, { month: 'short' });
    const d = document.createElement('span');
    d.className = 'date-day';
    d.textContent = date.getDate();
    badge.append(mon, d);

    const info = document.createElement('span');
    info.className = 'upcoming-info';
    const name = document.createElement('span');
    name.className = 'upcoming-name';
    name.textContent = ev.name;
    const meta = document.createElement('span');
    meta.className = 'upcoming-meta';
    meta.textContent = `${ev.venue} · ${date.toLocaleDateString(undefined, { weekday: 'short' })} ${TIME_FMT.format(date)}`;
    info.append(name, meta);

    const avail = document.createElement('span');
    const left = ev.seats.available;
    avail.className = `availability${left === 0 ? ' sold-out' : left <= ev.totalSeats * 0.2 ? ' low' : ''}`;
    avail.textContent = left === 0 ? 'Sold out' : `${left} left · ${money(ev.price)}`;

    btn.append(badge, info, avail);
    li.append(btn);
    list.append(li);
  }
}

function shiftMonth(delta) {
  state.calMonth = new Date(state.calMonth.getFullYear(), state.calMonth.getMonth() + delta, 1);
  renderCalendar();
}

async function selectEvent(id, { fromHash = false } = {}) {
  if (id === state.eventId) return;
  state.eventId = id;
  state.event = null;
  state.seats = new Map();
  state.seatsEventId = null;
  state.mine = [];
  state.showHistory = false;
  const ev = state.events.find((e) => e.id === id);
  if (ev) {
    const d = new Date(ev.startTime);
    state.calMonth = new Date(d.getFullYear(), d.getMonth(), 1);
  }
  if (!fromHash) history.replaceState(null, '', `#event-${id}`);
  renderCalendar();
  renderUpcoming();
  renderMine();
  await refreshAll();
  connectSocket();
}

function eventFromHash() {
  const m = /^#event-(\d+)$/.exec(location.hash);
  return m ? Number(m[1]) : null;
}

// ---------- admin: create event ----------

async function createEvent(e) {
  e.preventDefault();
  const form = e.currentTarget;
  const error = $('create-error');
  error.hidden = true;
  if (!form.checkValidity()) {
    error.textContent = 'Fill in every field. Rows 1-26, seats per row 1-20, a start time in the future.';
    error.hidden = false;
    return;
  }
  const body = {
    name: form.name.value.trim(),
    venue: form.venue.value.trim(),
    startTime: new Date(form.startTime.value).toISOString(),
    rows: Number(form.rows.value),
    seatsPerRow: Number(form.seatsPerRow.value),
    price: Number(form.price.value),
  };
  const submit = form.querySelector('button[type="submit"]');
  submit.disabled = true;
  try {
    const { data } = await api('/api/admin/events', { method: 'POST', body });
    toast(`Created "${data.name}" with ${data.totalSeats} seats.`, 'success');
    form.reset();
    form.closest('details').open = false;
    await loadEvents();
    await selectEvent(data.id);
  } catch (err) {
    error.textContent = err.status === 400 ? 'Check the fields: the start time must be in the future.' : err.message;
    error.hidden = false;
  } finally {
    submit.disabled = false;
  }
}

// ---------- live updates ----------

function connectSocket() {
  clearTimeout(state.reconnectTimer);
  if (state.socket) {
    state.socket.intentional = true;   // switching events: do not auto-reconnect the old one
    state.socket.close();
  }
  const proto = location.protocol === 'https:' ? 'wss' : 'ws';
  const socket = new WebSocket(`${proto}://${location.host}/ws/events/${state.eventId}`);
  state.socket = socket;
  setLive('connecting');
  socket.addEventListener('open', () => {
    state.socketRetry = 0;
    setLive('open');
    refreshAll();   // catch up on anything missed while disconnected
  });
  socket.addEventListener('message', (msg) => {
    let update;
    try { update = JSON.parse(msg.data); } catch { return; }
    if (update.type === 'RESET') {
      toast('The event was reset by an admin.');
      refreshAll();
      return;
    }
    if (update.type === 'SEAT' && state.seats.has(update.seatNo)) {
      state.seats.set(update.seatNo, update.status);
      renderSeats();
      flashSeat(update.seatNo);
      if (state.mine.some((b) => b.seatNo === update.seatNo)) loadMine().catch(() => {});
    }
  });
  socket.addEventListener('close', () => {
    if (socket.intentional) return;
    setLive('closed');
    const delay = Math.min(15000, 1000 * 2 ** state.socketRetry++);
    state.reconnectTimer = setTimeout(connectSocket, delay);
  });
}

function setLive(s) {
  const el = $('live');
  el.dataset.state = s;
  el.querySelector('.live-text').textContent = s === 'open' ? 'Live' : s === 'connecting' ? 'Connecting…' : 'Reconnecting…';
}

async function refreshAll() {
  try {
    await Promise.all([loadEvents(), loadEvent(), loadSeats(), loadMine()]);
  } catch (e) {
    toast(`Could not reach the server: ${e.message}`, 'error');
  }
}

// ---------- auth ----------

let authMode = 'login';

function renderAuth() {
  const area = $('auth-area');
  area.textContent = '';
  if (state.user) {
    const chip = document.createElement('span');
    chip.className = 'user-chip';
    const avatar = document.createElement('span');
    avatar.className = 'avatar';
    avatar.textContent = state.user.name.slice(0, 1).toUpperCase();
    const name = document.createElement('span');
    name.className = 'user-name';
    name.textContent = state.user.name;
    chip.append(avatar, name);
    if (state.user.admin) {
      const badge = document.createElement('span');
      badge.className = 'badge';
      badge.textContent = 'Admin';
      chip.append(badge);
    }
    const out = document.createElement('button');
    out.type = 'button';
    out.className = 'btn ghost small';
    out.textContent = 'Sign out';
    out.addEventListener('click', signOut);
    area.append(chip, out);
  } else {
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'btn primary small';
    btn.textContent = 'Sign in';
    btn.addEventListener('click', () => openAuth('login'));
    area.append(btn);
  }
  $('admin').hidden = !state.user?.admin;
  $('verify-banner').hidden = !(state.user?.email && !state.user.emailVerified);
  if (state.user?.email) $('verify-email').textContent = state.user.email;
}

async function resendVerification(e) {
  const btn = e.currentTarget;
  btn.disabled = true;
  try {
    await api('/api/auth/resend-verification', { method: 'POST' });
    toast(`Sent a new link to ${state.user.email}.`, 'success');
  } catch (err) {
    toast(err.status === 429 ? 'Please wait a little before asking for another link.' : err.message, 'error');
  } finally {
    btn.disabled = false;
  }
}

function openAuth(mode) {
  setAuthMode(mode);
  $('auth-error').hidden = true;
  $('auth-dialog').showModal();
  $('auth-form').username.focus();
}

function setAuthMode(mode) {
  authMode = mode;
  const register = mode === 'register';
  $('auth-title').textContent = register ? 'Create account' : 'Sign in';
  $('auth-submit').textContent = register ? 'Create account' : 'Sign in';
  $('password-hint').hidden = !register;
  $('email-field').hidden = !register;
  $('auth-form').password.autocomplete = register ? 'new-password' : 'current-password';
  for (const tab of document.querySelectorAll('.tab')) tab.setAttribute('aria-selected', String(tab.dataset.mode === mode));
}

async function submitAuth(event) {
  event.preventDefault();
  const form = $('auth-form');
  const username = form.username.value.trim();
  const password = form.password.value;
  const email = form.email.value.trim();
  const error = $('auth-error');
  error.hidden = true;
  if (!username || !password) {
    error.textContent = 'Enter a username and a password.';
    error.hidden = false;
    return;
  }
  if (authMode === 'register' && !(password.length >= 8 && /[A-Za-z]/.test(password) && /\d/.test(password))) {
    error.textContent = 'Use at least 8 characters, with a letter and a number.';
    error.hidden = false;
    return;
  }
  if (authMode === 'register' && email && !form.email.checkValidity()) {
    error.textContent = 'That email address does not look right.';
    error.hidden = false;
    return;
  }
  const submit = $('auth-submit');
  submit.disabled = true;
  try {
    if (authMode === 'register') {
      await api('/api/auth/register', { method: 'POST', body: { username, password, email: email || null } });
    }
    const { data } = await api('/api/auth/login', { method: 'POST', body: { username, password } });
    setUser(data.user);   // the token itself arrives as an httpOnly cookie; data.token is ignored
    $('auth-dialog').close();
    form.reset();
    toast(authMode === 'register' && email
      ? `Welcome, ${state.user.name}! Check ${email} to confirm your address.`
      : `Welcome, ${state.user.name}!`);
    await loadMine();
    const seat = state.pendingSeatAfterLogin;
    state.pendingSeatAfterLogin = null;
    if (seat && state.seats.get(seat) === 'AVAILABLE') await holdSeat(seat);
  } catch (e) {
    error.textContent = e.status === 401 ? 'Wrong username or password.'
      : e.status === 409 ? 'That username is taken.'
      : e.status === 429 ? e.message
      : e.status === 400 ? (e.message.startsWith('Password') ? e.message : 'Please check the username (3-32 letters, digits, . _ -), password and email.')
      : e.message;
    error.hidden = false;
  } finally {
    submit.disabled = false;
  }
}

// ---------- admin ----------

async function adminAction(path, button, done) {
  button.disabled = true;
  try {
    const { data } = await api(path, { method: 'POST' });
    toast(done(data), 'success');
    await refreshAll();
  } catch (e) {
    toast(e.message, 'error');
  } finally {
    button.disabled = false;
  }
}

// ---------- toasts ----------

function toast(message, kind = 'info') {
  const el = document.createElement('div');
  el.className = `toast ${kind}`;
  el.textContent = message;
  $('toasts').append(el);
  setTimeout(() => el.remove(), 4000);
  while ($('toasts').children.length > 3) $('toasts').firstChild.remove();
}

// ---------- start ----------

function wireUp() {
  $('auth-form').addEventListener('submit', submitAuth);
  $('btn-resend').addEventListener('click', resendVerification);
  $('auth-close').addEventListener('click', () => $('auth-dialog').close());
  for (const tab of document.querySelectorAll('.tab')) tab.addEventListener('click', () => setAuthMode(tab.dataset.mode));
  $('btn-reset').addEventListener('click', (e) => {
    if (confirm('Reset the event? All bookings and holds will be deleted.')) {
      adminAction(`/api/admin/events/${state.eventId}/reset`, e.currentTarget, () => 'Event reset: all seats available.');
    }
  });
  $('btn-remind').addEventListener('click', (e) =>
    adminAction(`/api/admin/events/${state.eventId}/remind`, e.currentTarget,
      (d) => d.remindersQueued ? `Queued ${d.remindersQueued} reminder email(s).` : 'No reminders queued (none confirmed, or already sent).'));
  $('create-form').addEventListener('submit', createEvent);
  $('cal-prev').addEventListener('click', () => shiftMonth(-1));
  $('cal-next').addEventListener('click', () => shiftMonth(1));
  $('cal-today').addEventListener('click', () => {
    const now = new Date();
    state.calMonth = new Date(now.getFullYear(), now.getMonth(), 1);
    renderCalendar();
  });
  window.addEventListener('hashchange', () => {
    const id = eventFromHash();
    if (id && id !== state.eventId) selectEvent(id, { fromHash: true });
  });
  $('btn-reconcile').addEventListener('click', (e) =>
    adminAction(`/api/admin/events/${state.eventId}/reconcile`, e.currentTarget,
      (d) => `Reconciliation done: ${d.repairedSeats} seat(s) repaired in Redis.`));

  setInterval(tickCountdowns, 1000);
  // Payment results arrive asynchronously; poll while any payment is in flight.
  setInterval(() => {
    if (state.mine.some((b) => b.status === 'PAYMENT_PENDING')) loadMine().catch(() => {});
  }, 1500);
  // Safety net if the WebSocket is down.
  setInterval(() => {
    if (state.socket?.readyState !== WebSocket.OPEN) loadSeats().catch(() => {});
  }, 5000);
}

function showVerificationResult() {
  if (location.hash === '#email-verified') {
    toast('Email confirmed. Your tickets will be emailed to you.', 'success');
  } else if (location.hash === '#email-verify-failed') {
    toast('That confirmation link is invalid or has expired. Sign in and ask for a new one.', 'error');
  } else {
    return;
  }
  history.replaceState(null, '', location.pathname);
}

async function start() {
  wireUp();
  try { localStorage.removeItem(LEGACY_TOKEN_KEY); } catch { /* storage may be unavailable */ }
  showVerificationResult();
  await loadMe();
  try {
    await loadEvents();
  } catch (e) {
    toast(`Could not reach the server: ${e.message}`, 'error');
    return;
  }
  const requested = eventFromHash();
  const pick = state.events.find((e) => e.id === requested)
    || state.events.find((e) => e.onSale)
    || state.events[0];
  if (!pick) {
    $('event-name').textContent = 'No events yet';
    $('event-meta').textContent = '';
    return;
  }
  await selectEvent(pick.id, { fromHash: pick.id === requested });
}

start();
