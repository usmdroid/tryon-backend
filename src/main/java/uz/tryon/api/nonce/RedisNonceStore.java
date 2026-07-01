package uz.tryon.api.nonce;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/**
 * Redis-da nonce saqlash — bir nechta server uchun to'g'ri.
 * SET nonce "1" NX EX <ttl> — atomik, faqat birinchi marta muvaffaqiyatli.
 */
public class RedisNonceStore implements NonceStore {

    private final StringRedisTemplate redis;
    private final long bufferSeconds;

    public RedisNonceStore(StringRedisTemplate redis, long bufferSeconds) {
        this.redis = redis;
        this.bufferSeconds = bufferSeconds;
    }

    @Override
    public boolean tryConsume(String nonce, long expEpochMs) {
        long now = System.currentTimeMillis();
        long ttl = Math.max(1, (expEpochMs - now) / 1000 + bufferSeconds);
        Boolean set = redis.opsForValue().setIfAbsent("nonce:" + nonce, "1", Duration.ofSeconds(ttl));
        return Boolean.TRUE.equals(set);
    }
}
