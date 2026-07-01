package uz.tryon.api.nonce;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Optional;

/**
 * Redis sozlangan bo'lsa → RedisNonceStore; aks holda → InMemoryNonceStore.
 * RateLimiterService bilan bir xil ulanishni qayta ishlatadi.
 */
@Configuration
public class NonceStoreConfig {

    private static final Logger log = LoggerFactory.getLogger(NonceStoreConfig.class);

    @Bean
    public NonceStore nonceStore(Optional<StringRedisTemplate> redisTemplate) {
        if (redisTemplate.isPresent()) {
            log.info("NonceStore: Redis rejimida");
            return new RedisNonceStore(redisTemplate.get(), 60);
        }
        log.info("NonceStore: xotirada (in-memory) rejimida");
        return new InMemoryNonceStore();
    }
}
