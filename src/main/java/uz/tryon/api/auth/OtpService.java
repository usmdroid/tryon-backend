package uz.tryon.api.auth;

import org.springframework.stereotype.Service;
import uz.tryon.api.AppConfig;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OTP (bir martalik tasdiqlash kodi) — telefonni tasdiqlash uchun.
 *
 * Kod generatsiya qilinadi, yetkaziladi ({@link OtpSender}) va xotirada saqlanadi
 * (qisqa muddatli). Hozir in-memory (bitta server uchun yetarli); ko'p server bo'lsa
 * Redis kerak bo'ladi.
 *
 * Dev/test uchun: tryon.otp-fixed-code o'rnatilsa, tasodifiy o'rniga o'sha kod ishlatiladi.
 */
@Service
public class OtpService {

    private static final int RESEND_COOLDOWN_MS = 60_000; // 1 daqiqa
    private static final int MAX_ATTEMPTS = 5;

    private final OtpSender sender;
    private final AppConfig config;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Entry> store = new ConcurrentHashMap<>();

    public OtpService(OtpSender sender, AppConfig config) {
        this.sender = sender;
        this.config = config;
    }

    public static class TooSoonException extends RuntimeException { }

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

    /** Kod yaratib telefonga yuboradi va kodni qaytaradi. Juda tez-tez so'ralsa — TooSoonException. */
    public String sendCode(String phone) {
        String p = Phones.normalize(phone);
        long now = System.currentTimeMillis();
        String fixed = config.getOtpFixedCode();
        boolean devMode = fixed != null && !fixed.isBlank();

        Entry prev = store.get(p);
        if (!devMode && prev != null && now - prev.sentAt < RESEND_COOLDOWN_MS) {
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
    public boolean verify(String phone, String code) {
        String p = Phones.normalize(phone);
        Entry e = store.get(p);
        if (e == null) return false;
        long now = System.currentTimeMillis();
        if (now > e.expiresAt || e.attempts >= MAX_ATTEMPTS) {
            store.remove(p);
            return false;
        }
        if (code == null || !constantTimeEquals(e.code, code.trim())) {
            e.attempts++;
            return false;
        }
        store.remove(p); // muvaffaqiyat — bir martalik
        return true;
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
