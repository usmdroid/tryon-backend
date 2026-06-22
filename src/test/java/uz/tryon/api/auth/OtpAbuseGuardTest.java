package uz.tryon.api.auth;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OtpAbuseGuard birlik testi — xotira rejimida (Redis'siz), soxta Clock bilan.
 * Vaqt soxta soat orqali oldinga suriladi, uxlamaymiz.
 */
class OtpAbuseGuardTest {

    private static final String EMAIL = "abuser@dokon.uz";
    private static final long MIN = 60_000L;

    /** Qo'lda boshqariladigan soat — testlar vaqtni xohlagancha oldinga suradi. */
    private static final class MutableClock extends Clock {
        private long ms;
        MutableClock(long startMs) { this.ms = startMs; }
        void advance(long deltaMs) { this.ms += deltaMs; }
        @Override public long millis() { return ms; }
        @Override public Instant instant() { return Instant.ofEpochMilli(ms); }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
    }

    private OtpAbuseGuard guard(MutableClock clock) {
        return new OtpAbuseGuard((org.springframework.data.redis.core.StringRedisTemplate) null, clock);
    }

    /** Bir daqiqada uchta breach -> bloklanadi, birinchi blok ~2 daqiqa. */
    @Test
    void uch_breach_bir_daqiqada_bloklaydi_2_daqiqa() {
        MutableClock clock = new MutableClock(0);
        OtpAbuseGuard g = guard(clock);

        g.recordBreach(EMAIL);
        clock.advance(1000);
        g.recordBreach(EMAIL);
        assertFalse(g.isBlocked(EMAIL), "ikki breach hali bloklamasligi kerak");
        clock.advance(1000);
        g.recordBreach(EMAIL); // uchinchi -> blok

        assertTrue(g.isBlocked(EMAIL));
        long rem = g.remainingBlockMs(EMAIL);
        assertEquals(2 * MIN, rem, 1500, "birinchi blok ~2 daqiqa");
    }

    /** 2 daqiqa blokdan keyin yana 3 breach -> ~15 daqiqa; yana -> ~1 soat. */
    @Test
    void eskalatsiya_zinapoyasi_2_15_60() {
        MutableClock clock = new MutableClock(0);
        OtpAbuseGuard g = guard(clock);

        // 1-eskalatsiya: 2 daqiqa
        breachThrice(g, clock);
        assertEquals(2 * MIN, g.remainingBlockMs(EMAIL), 1500);

        // Birinchi blok tugashini kutamiz (vaqtni surib).
        clock.advance(2 * MIN + 1000);
        assertFalse(g.isBlocked(EMAIL));

        // 2-eskalatsiya: 15 daqiqa
        breachThrice(g, clock);
        assertEquals(15 * MIN, g.remainingBlockMs(EMAIL), 1500);

        clock.advance(15 * MIN + 1000);
        assertFalse(g.isBlocked(EMAIL));

        // 3-eskalatsiya: 1 soat
        breachThrice(g, clock);
        assertEquals(60 * MIN, g.remainingBlockMs(EMAIL), 1500);

        clock.advance(60 * MIN + 1000);
        assertFalse(g.isBlocked(EMAIL));

        // 4-eskalatsiya: zinapoya oxirgi pog'onada qoladi (1 soat)
        breachThrice(g, clock);
        assertEquals(60 * MIN, g.remainingBlockMs(EMAIL), 1500);
    }

    /** Muvaffaqiyatli tekshiruv breach hisoblagichi + eskalatsiya darajasini nolga tushiradi. */
    @Test
    void muvaffaqiyat_hisoblagichni_va_darajani_tozalaydi() {
        MutableClock clock = new MutableClock(0);
        OtpAbuseGuard g = guard(clock);

        // 1-eskalatsiya: 2 daqiqa
        breachThrice(g, clock);
        assertTrue(g.isBlocked(EMAIL));

        // Blok tugaydi, keyin muvaffaqiyat -> hammasi nolga tushadi.
        clock.advance(2 * MIN + 1000);
        g.recordSuccess(EMAIL);
        assertFalse(g.isBlocked(EMAIL));

        // Endi yangi 3 breach yana 2 daqiqadan boshlanadi (15 emas).
        breachThrice(g, clock);
        assertEquals(2 * MIN, g.remainingBlockMs(EMAIL), 1500, "muvaffaqiyatdan keyin daraja nolga tushgan");
    }

    /** Eskalatsiya darajasi 24 soatlik yaxshi xulqdan keyin tushadi (decay). */
    @Test
    void eskalatsiya_24_soatdan_keyin_tushadi() {
        MutableClock clock = new MutableClock(0);
        OtpAbuseGuard g = guard(clock);

        // 1-eskalatsiya: 2 daqiqa (daraja endi 1)
        breachThrice(g, clock);
        assertEquals(2 * MIN, g.remainingBlockMs(EMAIL), 1500);

        // 24 soat yaxshi xulq — eskalatsiya darajasi tushishi kerak.
        clock.advance(24L * 60 * 60 * 1000 + 1000);
        assertFalse(g.isBlocked(EMAIL));

        // Yangi eskalatsiya yana 2 daqiqadan boshlanadi (15 emas).
        breachThrice(g, clock);
        assertEquals(2 * MIN, g.remainingBlockMs(EMAIL), 1500, "24h decay'dan keyin yana 2 daqiqa");
    }

    /** Yordamchi: 1 daqiqa oynasi ichida ketma-ket 3 breach. */
    private void breachThrice(OtpAbuseGuard g, MutableClock clock) {
        g.recordBreach(EMAIL);
        clock.advance(1000);
        g.recordBreach(EMAIL);
        clock.advance(1000);
        g.recordBreach(EMAIL);
    }
}
