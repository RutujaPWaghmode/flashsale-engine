-- Compare-and-delete: only the owner can release a hold (or a sale being rolled back).
-- KEYS[1] = seat key, KEYS[2] = user's hold set, ARGV[1] = userId
local owner = redis.call('GET', KEYS[1])
if owner == ARGV[1] or owner == ('SOLD:' .. ARGV[1]) then
    redis.call('DEL', KEYS[1])
    redis.call('SREM', KEYS[2], KEYS[1])
    return 1
end
return 0
