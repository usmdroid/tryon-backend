package uz.tryon.api.nonce;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RedisNonceStoreTest {

    private static final long EXP = System.currentTimeMillis() + 300_000;

    @SuppressWarnings("unchecked")
    private RedisNonceStore storeWith(boolean setIfAbsentResult) {
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(ops.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(setIfAbsentResult);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenReturn(ops);
        return new RedisNonceStore(redis, 60);
    }

    @Test
    void firstUse_ok() {
        assertTrue(storeWith(true).tryConsume("nonce-abc", EXP));
    }

    @Test
    void secondUse_rejected() {
        assertFalse(storeWith(false).tryConsume("nonce-abc", EXP));
    }

    @Test
    void ttlIncludesBuffer() {
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(ops.setIfAbsent(eq("nonce:x"), eq("1"), any(Duration.class))).thenReturn(true);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenReturn(ops);

        RedisNonceStore store = new RedisNonceStore(redis, 60);
        store.tryConsume("x", EXP);

        verify(ops).setIfAbsent(eq("nonce:x"), eq("1"), argThat(d -> d.getSeconds() > 0));
    }
}
