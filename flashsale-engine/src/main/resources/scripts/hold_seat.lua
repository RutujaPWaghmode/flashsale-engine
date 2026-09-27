-- Atomically hold a seat, enforcing a per-user seat cap for the event.
-- KEYS[1] = seat key          e.g. seat:{evt1}:A01
-- KEYS[2] = user's hold set   e.g. user-holds:{evt1}:alice
-- ARGV[1] = userId, ARGV[2] = hold TTL in seconds, ARGV[3] = max seats per user
-- Returns 1 = held, 0 = seat taken, -1 = user reached the cap.
-- The GETs below touch keys not listed in KEYS. That is fine on one node; on Redis Cluster the
-- {evtN} hash tag keeps every key of an event in the same slot.
if redis.call('EXISTS', KEYS[1]) == 1 then
    return 0
end

local user = ARGV[1]
local sold = 'SOLD:' .. user
local active = 0
for _, seatKey in ipairs(redis.call('SMEMBERS', KEYS[2])) do
    local owner = redis.call('GET', seatKey)
    if owner == user or owner == sold then
        active = active + 1
    else
        redis.call('SREM', KEYS[2], seatKey)   -- hold expired or was released
    end
end
if active >= tonumber(ARGV[3]) then
    return -1
end

redis.call('SET', KEYS[1], user, 'EX', ARGV[2])
redis.call('SADD', KEYS[2], KEYS[1])
redis.call('EXPIRE', KEYS[2], 86400)
return 1
