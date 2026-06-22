package uz.tryon.api;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** RateLimiterService — xotirada (in-memory) rejimi uchun birlik testlari. */
class RateLimiterServiceTest {

    private RateLimiterService build(int limitPerMinute) {
        AppConfig cfg = new AppConfig();
        cfg.setRateLimitPerMinute(limitPerMinute);
        // Redis yo'q — Optional.empty() = in-memory rejim
        return new RateLimiterService(cfg, Optional.empty());
    }

    @Test
    void limit_ichdida_ruxsat() {
        RateLimiterService svc = build(3);
        assertTrue(svc.allow("key1"));
        assertTrue(svc.allow("key1"));
        assertTrue(svc.allow("key1"));
    }

    @Test
    void limit_oshsa_rad() {
        RateLimiterService svc = build(3);
        svc.allow("key1");
        svc.allow("key1");
        svc.allow("key1");
        assertFalse(svc.allow("key1")); // 4-chi so'rov — limit oshdi
    }

    @Test
    void turli_kalitlar_mustaqil() {
        RateLimiterService svc = build(1);
        assertTrue(svc.allow("key1"));
        assertFalse(svc.allow("key1")); // key1 uchun limit oshdi
        assertTrue(svc.allow("key2")); // key2 alohida hisoblagich
    }
}
