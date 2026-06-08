package uz.tryon.api;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rate limiting — har API kalit uchun alohida "bucket".
 *
 * Bu eng muhim himoya: GPU xarajatini portlashdan saqlaydi.
 * Bitta kalit daqiqasiga belgilangan sondan ortiq so'rov yubora olmaydi.
 *
 * Eslatma: hozir xotirada (in-memory). Bir nechta server bo'lsa (scale),
 * Redis bilan umumiy bucket kerak bo'ladi — backend dev buni keyin qo'shadi.
 */
@Service
public class RateLimiterService {

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final AppConfig config;

    public RateLimiterService(AppConfig config) {
        this.config = config;
    }

    /** Shu kalit hozir so'rov yubora oladimi? false = limit oshgan. */
    public boolean allow(String apiKey) {
        Bucket bucket = buckets.computeIfAbsent(apiKey, k -> newBucket());
        return bucket.tryConsume(1);
    }

    private Bucket newBucket() {
        Bandwidth limit = Bandwidth.builder()
                .capacity(config.getRateLimitPerMinute())
                .refillGreedy(config.getRateLimitPerMinute(), Duration.ofMinutes(1))
                .build();
        return Bucket.builder().addLimit(limit).build();
    }
}
