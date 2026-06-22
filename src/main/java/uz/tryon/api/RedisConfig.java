package uz.tryon.api;

import io.lettuce.core.RedisURI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis ulanishi — faqat REDIS_URL sozlangan bo'lsa yaratiladi.
 * Bo'lmasa Spring konteksti muvaffaqiyatli ishga tushadi (in-memory fallback).
 */
@Configuration
public class RedisConfig {

    private static final Logger log = LoggerFactory.getLogger(RedisConfig.class);

    /** Redis URL mavjud va bo'sh bo'lmagan bo'lsa ulanish factory'si yaratiladi. */
    @Bean
    @ConditionalOnExpression("!'${tryon.redis-url:}'.equals('')")
    public LettuceConnectionFactory redisConnectionFactory(AppConfig config) {
        String url = config.getRedisUrl();
        log.info("Redis ulanishi sozlanmoqda");
        RedisURI uri = RedisURI.create(url);
        RedisStandaloneConfiguration rc = new RedisStandaloneConfiguration(uri.getHost(), uri.getPort());
        if (uri.getPassword() != null && uri.getPassword().length > 0) {
            rc.setPassword(new String(uri.getPassword()));
        }
        if (uri.getDatabase() > 0) {
            rc.setDatabase(uri.getDatabase());
        }
        return new LettuceConnectionFactory(rc);
    }

    @Bean
    @ConditionalOnExpression("!'${tryon.redis-url:}'.equals('')")
    public StringRedisTemplate stringRedisTemplate(LettuceConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }
}
