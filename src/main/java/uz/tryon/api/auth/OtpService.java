package uz.tryon.api.auth;

import org.springframework.stereotype.Service;
import uz.tryon.api.AppConfig;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OTP (bir martalik tasdiqlash kodi) — email manzilni tasdiqlash uchun.
 *
 * Kod generatsiya qilinadi, yetkaziladi ({@link OtpSender} — email orqali) va xotirada
 * saqlanadi (qisqa muddatli). Xotira email bo'yicha kalitlanadi. Hozir in-memory
 * (bitta server uchun yetarli); ko'p server bo'lsa Redis kerak bo'ladi.
 *
 * Dev/test uchun: tryon.otp-fixed-code o'rnatilsa, tasodifiy o'rniga o'sha kod ishlatiladi.
 */
@Service
public class OtpService {

    private static final int RESEND_COOLDOWN_MS = 60_000; // 1 daqiqa
    private static final int MAX_ATTEMPTS = 5;

    private final OtpSender sender;
    private final AppConfig config;
    private final OtpAbuseGuard abuseGuard;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Entry> store = new ConcurrentHashMap<>();

    public OtpService(OtpSender sender, AppConfig config, OtpAbuseGuard abuseGuard) {
        this.sender = sender;
        this.config = config;
        this.abuseGuard = abuseGuard;
    }

    public static class TooSoonException extends RuntimeException { }

    /** Email suiiste'mol uchun bloklangan — qoldirilgan millisekundni tashiydi. */
    public static class BlockedException extends RuntimeException {
        private final long remainingMs;
        public BlockedException(long remainingMs) { this.remainingMs = remainingMs; }
        public long getRemainingMs() { return remainingMs; }
        /** Qoldirilgan vaqt soniyada (yuqoriga yaxlitlanadi). */
        public long getRemainingSeconds() { return (remainingMs + 999) / 1000; }
    }

    private static final class Entry {
        final String code;
        final long expiresAt;
        final long sentAt;
        int attempts;
        Entry(String code, long expiresAt, long sentAt) {
            this.code = code;
            this.expiresAt = expiresAt;
            this.sentAt = sentAt;
        }
    }

    /** Email manzilni bir xil ko'rinishga keltirish (OTP bir kalitda bo'lsin): trim + lowercase. */
    public static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    /** Admin uchun: berilgan email bo'yicha blok + breach + eskalatsiya darajasini tozalaydi. */
    public void unblock(String email) {
        abuseGuard.reset(normalizeEmail(email));
    }

    /** Kod yaratib email manzilga yuboradi va kodni qaytaradi. Juda tez-tez so'ralsa — TooSoonException. */
    public String sendCode(String email) {
        String p = normalizeEmail(email);
        long now = System.currentTimeMillis();
        String fixed = config.getOtpFixedCode();
        boolean devMode = fixed != null && !fixed.isBlank();

        // Avval blokni tekshiramiz — bloklangan bo'lsa kod ham yubormaymiz.
        long blockMs = abuseGuard.remainingBlockMs(p);
        if (blockMs > 0) {
            throw new BlockedException(blockMs);
        }

        Entry prev = store.get(p);
        if (!devMode && prev != null && now - prev.sentAt < RESEND_COOLDOWN_MS) {
            // Cooldown ichida qayta-yuborish = breach (spamdan himoya). devMode'da hisoblanmaydi.
            abuseGuard.recordBreach(p);
            // Breach bloknı keltirib chiqargan bo'lishi mumkin — darhol bildiramiz.
            long after = abuseGuard.remainingBlockMs(p);
            if (after > 0) {
                throw new BlockedException(after);
            }
            throw new TooSoonException(); // cooldown faqat production'da (tasodifiy kod)
        }
        String code = devMode
                ? fixed
                : String.format("%06d", random.nextInt(1_000_000));
        store.put(p, new Entry(code, now + config.getOtpTtlSeconds() * 1000, now));
        sender.send(p, code);
        return code;
    }

    /** Kodni tekshiradi; to'g'ri bo'lsa "iste'mol" qilinadi (qayta ishlatilmaydi). */
    public boolean verify(String email, String code) {
        String p = normalizeEmail(email);

        // Bloklangan bo'lsa tekshiruvga ham yo'l yo'q (brute-force'dan himoya).
        long blockMs = abuseGuard.remainingBlockMs(p);
        if (blockMs > 0) {
            throw new BlockedException(blockMs);
        }

        Entry e = store.get(p);
        if (e == null) {
            abuseGuard.recordBreach(p); // kod yo'q/eskirgan — noto'g'ri urinish = breach
            throwIfBlocked(p);
            return false;
        }
        long now = System.currentTimeMillis();
        if (now > e.expiresAt || e.attempts >= MAX_ATTEMPTS) {
            store.remove(p);
            abuseGuard.recordBreach(p);
            throwIfBlocked(p);
            return false;
        }
        if (code == null || !constantTimeEquals(e.code, code.trim())) {
            e.attempts++;
            abuseGuard.recordBreach(p); // noto'g'ri kod = breach
            throwIfBlocked(p);
            return false;
        }
        store.remove(p); // muvaffaqiyat — bir martalik
        abuseGuard.recordSuccess(p); // breach hisoblagichi + eskalatsiya darajasi nolga tushadi
        return true;
    }

    /** Breach blokni keltirib chiqargan bo'lsa — darhol BlockedException tashlaydi. */
    private void throwIfBlocked(String normalizedEmail) {
        long after = abuseGuard.remainingBlockMs(normalizedEmail);
        if (after > 0) {
            throw new BlockedException(after);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
