-- Turn a hold into a permanent sale. Idempotent, because Kafka may redeliver the payment event.
-- KEYS[1] = seat key, ARGV[1] = userId
-- Returns 1 = sold to this user, 0 = the hold was lost (expired or taken by someone else).
local owner = redis.call('GET', KEYS[1])
local sold = 'SOLD:' .. ARGV[1]
if owner == sold then
    return 1
end
if owner == ARGV[1] then
    redis.call('SET', KEYS[1], sold)   -- no TTL: a sold seat never comes back
    return 1
end
return 0
