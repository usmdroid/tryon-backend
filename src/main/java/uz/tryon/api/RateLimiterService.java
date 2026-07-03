package uz.tryon.api;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rate limiting — har API kalit (clientId) uchun daqiqasiga cheklangan so'rov.
 *
 * Redis mavjud bo'lsa: umumiy hisoblagich (bir nechta server uchun to'g'ri).
 * Redis yo'q bo'lsa: xotira (in-memory) — lokal/dev/test uchun.
 *
 * Public interfeys o'zgarmaydi: callerlar faqat allow(clientId) chaqiradi.
 */
@Service
public class RateLimiterService {

    private static final Logger log = LoggerFactory.getLogger(RateLimiterService.class);

    /** Dev sandbox public endpoint: max requests per minute per IP. */
    private static final int DEV_SANDBOX_RATE_LIMIT_PER_MINUTE = 5;

    private final AppConfig config;
    private final StringRedisTemplate redisTemplate; // null = in-memory rejimi
    private final Map<String, Bucket> inMemoryBuckets = new ConcurrentHashMap<>();

    /** Atom INCR + birinchi so'rovda TTL o'rnatish (fixed-window, ~1 daqiqa). */
    private static final DefaultRedisScript<Long> RATE_SCRIPT;

    static {
        DefaultRedisScript<Long> s = new DefaultRedisScript<>();
        s.setScriptText(
            "local cur = redis.call('INCR', KEYS[1])\n" +
            "if cur == 1 then redis.call('EXPIRE', KEYS[1], 60) end\n" +
            "return cur"
        );
        s.setResultType(Long.class);
        RATE_SCRIPT = s;
    }

    public RateLimiterService(AppConfig config, Optional<StringRedisTemplate> redisTemplate) {
        this.config = config;
        this.redisTemplate = redisTemplate.orElse(null);
        if (this.redisTemplate != null) {
            log.info("Rate limiter: Redis rejimida (daqiqasiga {} so'rov)", config.getRateLimitPerMinute());
        } else {
            log.info("Rate limiter: xotirada (in-memory) rejimida");
        }
    }

    /** Shu clientId hozir so'rov yubora oladimi? false = limit oshgan. */
    public boolean allow(String clientId) {
        if (redisTemplate != null) {
            return allowWithRedis(clientId);
        }
        return allowInMemory(clientId);
    }

    /** Dev sandbox public endpoint: IP-based rate limiter (separate key prefix). */
    public boolean allowDevSandboxIp(String ip) {
        if (redisTemplate != null) {
            return allowDevSandboxIpWithRedis(ip);
        }
        return allowDevSandboxIpInMemory(ip);
    }

    private boolean allowWithRedis(String clientId) {
        // Kalit: daqiqa oynasi bo'yicha (fixed window)
        long window = System.currentTimeMillis() / 60_000L;
        String key = "rate:" + clientId + ":" + window;
        try {
            Long count = redisTemplate.execute(RATE_SCRIPT, List.of(key));
            return count != null && count <= config.getRateLimitPerMinute();
        } catch (Exception e) {
            // Redis muammo — in-memory ga tushib ketamiz, so'rov yo'qolmaydi
            log.error("Redis rate limit xatosi, in-memory ga o'tildi: {}", e.getMessage());
            return allowInMemory(clientId);
        }
    }

    private boolean allowDevSandboxIpWithRedis(String ip) {
        long window = System.currentTimeMillis() / 60_000L;
        String key = "ratelimit:devsandbox:ip:" + ip + ":" + window;
        try {
            Long count = redisTemplate.execute(RATE_SCRIPT, List.of(key));
            return count != null && count <= DEV_SANDBOX_RATE_LIMIT_PER_MINUTE;
        } catch (Exception e) {
            log.error("Redis dev sandbox rate limit xatosi, in-memory ga o'tildi: {}", e.getMessage());
            return allowDevSandboxIpInMemory(ip);
        }
    }

    private boolean allowInMemory(String clientId) {
        Bucket bucket = inMemoryBuckets.computeIfAbsent(clientId, k -> newBucket());
        return bucket.tryConsume(1);
    }

    private boolean allowDevSandboxIpInMemory(String ip) {
        String key = "devsandbox:" + ip;
        Bucket bucket = inMemoryBuckets.computeIfAbsent(key, k -> newDevSandboxBucket());
        return bucket.tryConsume(1);
    }

    private Bucket newBucket() {
        Bandwidth limit = Bandwidth.builder()
                .capacity(config.getRateLimitPerMinute())
                .refillGreedy(config.getRateLimitPerMinute(), Duration.ofMinutes(1))
                .build();
        return Bucket.builder().addLimit(limit).build();
    }

    private Bucket newDevSandboxBucket() {
        Bandwidth limit = Bandwidth.builder()
                .capacity(DEV_SANDBOX_RATE_LIMIT_PER_MINUTE)
                .refillGreedy(DEV_SANDBOX_RATE_LIMIT_PER_MINUTE, Duration.ofMinutes(1))
                .build();
        return Bucket.builder().addLimit(limit).build();
    }
}
