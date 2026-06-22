package uz.tryon.api.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OTP suiiste'molidan himoya — eskalatsiyalanuvchi blok bilan.
 *
 * Ikki suiiste'molni qoplaydi: (a) kod SO'RASH spamı (email kvotasini yondiradi),
 * (b) noto'g'ri KOD brute-force. Bitta eskalatsiya zinapoyasi, alohida "breach"
 * (buzilish) hisoblagichi bilan, email bo'yicha kalitlanadi.
 *
 * Siyosat:
 *   - breach = cooldown ichida qayta-yuborish YOKI noto'g'ri kod.
 *   - 1 daqiqa ichida 3 breach -> blokga eskalatsiya:
 *       1-eskalatsiya -> 2 daqiqa, keyingisi -> 15 daqiqa, undan keyin -> 1 soat.
 *   - Muvaffaqiyatli tekshiruvda breach hisoblagichi VA eskalatsiya darajasi nolga tushadi.
 *   - Eskalatsiya darajasi 24 soatlik yaxshi xulqdan keyin o'z-o'zidan tushadi (decay).
 *
 * Saqlash (RateLimiterService andozasini aynan takrorlaydi):
 *   Redis bo'lsa — serverlar orasida umumiy holat; bo'lmasa — xotirada (in-memory) fallback.
 *   Test profilida redis-url yo'q — to'liq xotirada ishlaydi.
 *
 * Vaqt manbasi (Clock) inject qilinadi — testlar uxlamasdan vaqtni oldinga surishi uchun.
 */
@Service
public class OtpAbuseGuard {

    private static final Logger log = LoggerFactory.getLogger(OtpAbuseGuard.class);

    /** 1 daqiqa ichida shu sondagi breach blokni keltirib chiqaradi. */
    private static final int BREACH_THRESHOLD = 3;
    /** Breach oynasi (soniya) — Redis EXPIRE va xotira oynasi uchun. */
    private static final long BREACH_WINDOW_MS = 60_000L;
    /** Eskalatsiya darajasi shuncha vaqtdan keyin tushadi (24 soat). */
    private static final long ESCALATION_DECAY_MS = 24L * 60 * 60 * 1000;

    /** Eskalatsiya zinapoyasi (millisekund): 2 daqiqa -> 15 daqiqa -> 1 soat (keyin ham 1 soat). */
    private static final long[] BLOCK_LADDER_MS = {
            2L * 60 * 1000,
            15L * 60 * 1000,
            60L * 60 * 1000
    };

    private static final String PREFIX = "otpabuse:";

    /** Atom INCR + birinchi breach'da TTL o'rnatish (RateLimiterService Lua andozasi). */
    private static final DefaultRedisScript<Long> BREACH_SCRIPT;

    /**
     * Atom eskalatsiya: darajani o'qiydi, zinapoyadan blok davomiyligini tanlaydi,
     * blok kalitini va keyingi darajani yozadi — hammasi bitta Redis chaqiruvida.
     * Bu poyga (race) holatida ikkita threshold-breach bir xil eski darajani
     * o'qib, bir xil eskalatsiya qilishining oldini oladi (single-threaded Redis).
     *
     * KEYS[1] = blockKey, KEYS[2] = levelKey
     * ARGV[1] = now (ms), ARGV[2] = level TTL (ms, 24h),
     * ARGV[3..] = blok zinapoyasi (ms) — oxirgi qiymat takror pog'onalar uchun.
     * Qaytaradi: tanlangan blockMs.
     */
    private static final DefaultRedisScript<Long> ESCALATE_SCRIPT;

    static {
        DefaultRedisScript<Long> s = new DefaultRedisScript<>();
        s.setScriptText(
            "local cur = redis.call('INCR', KEYS[1])\n" +
            "if cur == 1 then redis.call('EXPIRE', KEYS[1], 60) end\n" +
            "return cur"
        );
        s.setResultType(Long.class);
        BREACH_SCRIPT = s;

        DefaultRedisScript<Long> e = new DefaultRedisScript<>();
        e.setScriptText(
            "local lvl = tonumber(redis.call('GET', KEYS[2]) or '0')\n" +
            "local ladderCount = #ARGV - 2\n" +
            "local idx = lvl + 1\n" +              // zinapoya darajadan keyingi pog'ona; ARGV[3] = level 0
            "if idx > ladderCount then idx = ladderCount end\n" +
            "local blockMs = tonumber(ARGV[2 + idx])\n" +
            "local now = tonumber(ARGV[1])\n" +
            "local levelTtl = tonumber(ARGV[2])\n" +
            "redis.call('SET', KEYS[1], tostring(now + blockMs), 'PX', blockMs)\n" +
            "redis.call('SET', KEYS[2], tostring(lvl + 1), 'PX', levelTtl)\n" +
            "return blockMs"
        );
        e.setResultType(Long.class);
        ESCALATE_SCRIPT = e;
    }

    private final StringRedisTemplate redisTemplate; // null = in-memory rejimi
    private final Clock clock;

    // In-memory holat (Redis yo'q bo'lganda).
    private final ConcurrentHashMap<String, MemState> inMemory = new ConcurrentHashMap<>();

    /** Xotira rejimidagi har email holati. Hammasi clock bo'yicha boshqariladi. */
    private static final class MemState {
        long breachCount;        // joriy oynadagi breach soni
        long breachWindowStart;  // breach oynasi boshlangan vaqt (ms)
        int escalationLevel;     // 0 = hech qachon eskalatsiya bo'lmagan
        long escalationSetAt;    // eskalatsiya darajasi oxirgi marta o'rnatilgan vaqt (ms)
        long blockedUntil;       // blok tugaydigan vaqt (ms); 0 = blok yo'q
    }

    @org.springframework.beans.factory.annotation.Autowired
    public OtpAbuseGuard(Optional<StringRedisTemplate> redisTemplate) {
        this(redisTemplate.orElse(null), Clock.systemUTC());
    }

    /** Test uchun: fake Clock va Redis'siz (in-memory) qurish. */
    public OtpAbuseGuard(StringRedisTemplate redisTemplate, Clock clock) {
        this.redisTemplate = redisTemplate;
        this.clock = clock;
        if (this.redisTemplate != null) {
            log.info("OTP abuse guard: Redis rejimida");
        } else {
            log.info("OTP abuse guard: xotirada (in-memory) rejimida");
        }
    }

    private long now() {
        return clock.millis();
    }

    /** Email blokda bo'lsa qoldirilgan millisekundni qaytaradi, aks holda 0. */
    public long remainingBlockMs(String email) {
        long now = now();
        if (redisTemplate != null) {
            return remainingBlockMsRedis(email, now);
        }
        return remainingBlockMsMemory(email, now);
    }

    /** Email hozir blokdami? */
    public boolean isBlocked(String email) {
        return remainingBlockMs(email) > 0;
    }

    /**
     * Bitta breach hisoblaydi (cooldown ichida qayta-yuborish yoki noto'g'ri kod).
     * Chegaraga yetilsa — blokga eskalatsiya qiladi.
     */
    public void recordBreach(String email) {
        long now = now();
        if (redisTemplate != null) {
            recordBreachRedis(email, now);
        } else {
            recordBreachMemory(email, now);
        }
    }

    /** Muvaffaqiyatli tekshiruv — breach hisoblagichi va eskalatsiya darajasi nolga tushadi. */
    public void recordSuccess(String email) {
        reset(email);
    }

    /** Blok + breach hisoblagichi + eskalatsiya darajasini to'liq tozalaydi (admin unblock ham shu). */
    public void reset(String email) {
        if (redisTemplate != null) {
            try {
                redisTemplate.delete(List.of(
                        breachKey(email), blockKey(email), levelKey(email)));
                return;
            } catch (Exception e) {
                log.error("Redis reset xatosi, in-memory ga o'tildi: {}", e.getMessage());
            }
        }
        inMemory.remove(email);
    }

    // ---- In-memory yo'l (test profili va Redis'siz dev) ----

    private long remainingBlockMsMemory(String email, long now) {
        MemState st = inMemory.get(email);
        if (st == null) return 0;
        synchronized (st) {
            if (st.blockedUntil > now) {
                return st.blockedUntil - now;
            }
            return 0;
        }
    }

    private void recordBreachMemory(String email, long now) {
        MemState st = inMemory.computeIfAbsent(email, k -> new MemState());
        synchronized (st) {
            // 24 soatlik yaxshi xulqdan keyin eskalatsiya darajasini tushiramiz (decay).
            if (st.escalationLevel > 0 && now - st.escalationSetAt >= ESCALATION_DECAY_MS) {
                st.escalationLevel = 0;
            }
            // Breach oynasi (1 daqiqa) — eskirgan bo'lsa qaytadan boshlaymiz.
            if (st.breachCount == 0 || now - st.breachWindowStart >= BREACH_WINDOW_MS) {
                st.breachWindowStart = now;
                st.breachCount = 0;
            }
            st.breachCount++;
            if (st.breachCount >= BREACH_THRESHOLD) {
                escalateMemory(st, now);
                // Yangi blokdan keyin oynani tozalaymiz.
                st.breachCount = 0;
            }
        }
    }

    private void escalateMemory(MemState st, long now) {
        // Eskalatsiya decay'i bu yerda ham qo'llanadi (recordBreach allaqachon tozalagan bo'lishi mumkin).
        if (st.escalationLevel > 0 && now - st.escalationSetAt >= ESCALATION_DECAY_MS) {
            st.escalationLevel = 0;
        }
        long blockMs = blockDurationForLevel(st.escalationLevel);
        st.escalationLevel++;
        st.escalationSetAt = now;
        st.blockedUntil = now + blockMs;
    }

    // ---- Redis yo'l (production, ko'p server) ----

    private long remainingBlockMsRedis(String email, long now) {
        try {
            String v = redisTemplate.opsForValue().get(blockKey(email));
            if (v == null) return 0;
            long until = Long.parseLong(v);
            return until > now ? until - now : 0;
        } catch (Exception e) {
            log.error("Redis blok o'qish xatosi, in-memory ga o'tildi: {}", e.getMessage());
            return remainingBlockMsMemory(email, now);
        }
    }

    private void recordBreachRedis(String email, long now) {
        try {
            Long cur = redisTemplate.execute(BREACH_SCRIPT, List.of(breachKey(email)));
            // FAQAT aniq chegara-breach'da ishga tushadi (==). Lua INCR har bir
            // chaqiruvchiga oshirilgan qiymatni qaytargani uchun, delete'ga yetib
            // bormay turib kelgan 4, 5... breach'lar '>=' bilan qayta-eskalatsiya
            // qilib, darajalarni o'tkazib yuborardi. '==' bilan delete bilan
            // poyga qiladigan 4-inkrement no-op bo'ladi.
            if (cur != null && cur == BREACH_THRESHOLD) {
                escalateRedis(email, now);
                // Oynani tozalaymiz — keyingi blok yangi 3 breach talab qiladi.
                redisTemplate.delete(breachKey(email));
            }
        } catch (Exception e) {
            log.error("Redis breach xatosi, in-memory ga o'tildi: {}", e.getMessage());
            recordBreachMemory(email, now);
        }
    }

    private void escalateRedis(String email, long now) {
        // Daraja o'qish + zinapoyadan blok tanlash + blok/daraja yozish — bitta atom
        // Lua skriptida. Bu bir vaqtda kelgan threshold-breach'larning bir xil eski
        // darajani o'qib, qo'shaloq eskalatsiya qilishining (last-writer-wins) oldini oladi.
        // TTL (24h) tugagan bo'lsa daraja kaliti yo'q -> Lua 0 deb oladi (decay).
        java.util.List<String> argv = new java.util.ArrayList<>(2 + BLOCK_LADDER_MS.length);
        argv.add(Long.toString(now));
        argv.add(Long.toString(ESCALATION_DECAY_MS));
        for (long ms : BLOCK_LADDER_MS) {
            argv.add(Long.toString(ms));
        }
        redisTemplate.execute(
                ESCALATE_SCRIPT,
                List.of(blockKey(email), levelKey(email)),
                argv.toArray());
    }

    // ---- Yordamchilar ----

    /** Darajaga mos blok davomiyligi (zinapoya oxirgi pog'onada to'xtaydi). */
    private static long blockDurationForLevel(int level) {
        int idx = Math.min(level, BLOCK_LADDER_MS.length - 1);
        return BLOCK_LADDER_MS[idx];
    }

    private static String breachKey(String email) { return PREFIX + "breach:" + email; }
    private static String blockKey(String email)  { return PREFIX + "block:" + email; }
    private static String levelKey(String email)  { return PREFIX + "level:" + email; }
}
