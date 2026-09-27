-- Once payment starts, make sure the hold lives at least ARGV[2] ms longer.
-- KEYS[1] = seat key, ARGV[1] = userId, ARGV[2] = minimum remaining TTL in ms
-- Returns 1 = extended (or already long enough), 0 = not held by this user.
if redis.call('GET', KEYS[1]) ~= ARGV[1] then
    return 0
end
if redis.call('PTTL', KEYS[1]) < tonumber(ARGV[2]) then
    redis.call('PEXPIRE', KEYS[1], ARGV[2])
end
return 1
