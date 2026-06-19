package uz.tryon.api;

import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sessiya tokeni — do'kon serveri {@code /api/session} orqali oladi, brauzer esa
 * {@code /api/tryon} ga {@code Authorization: Bearer <token>} bilan keladi.
 * <p>
 * Token: HMAC-SHA256 bilan IMZOLANGAN (shifrlanmagan — ichida sir yo'q),
 * QISQA MUDDATLI (TTL) va BIR MARTALI (nonce). Secret kalit serverda qoladi.
 * <p>
 * Format: base64url(payload) + "." + base64url(hmac(payload))
 *   payload = clientId | expEpochMs | nonce
 *   clientId = apiKey'ning xeshi (secret kalit token ichiga TUSHMAYDI).
 * <p>
 * Eslatma: ishlatilgan nonce'lar xotirada saqlanadi (bitta server uchun). Ko'p server
 * (scale) bo'lsa, Redis kerak bo'ladi — keyin qo'shiladi.
 */
@Service
public class TokenService {

    private final AppConfig config;
    private final SecureRandom random = new SecureRandom();
    /** Ishlatilgan (consume qilingan) nonce'lar: nonce -> exp (epoch ms). */
    private final Map<String, Long> usedNonces = new ConcurrentHashMap<>();

    public TokenService(AppConfig config) {
        this.config = config;
    }

    public record Issued(String token, long expiresInSeconds) {}

    /**
     * Berilgan subject uchun yangi token zarb qiladi.
     * DB orqali ro'yxatdan o'tgan mijozlar uchun subject = real UUID string.
     * Legacy config kalitlar uchun subject = clientId(apiKey) (16-char hex).
     */
    public Issued mint(String subject) {
        long ttl = config.getTokenTtlSeconds();
        long exp = System.currentTimeMillis() + ttl * 1000;
        String payload = subject + "|" + exp + "|" + randomNonce();
        String token = b64(payload.getBytes(StandardCharsets.UTF_8)) + "." + b64(hmac(payload));
        return new Issued(token, ttl);
    }

    /**
     * Tokenni tekshiradi. Yaroqli bo'lsa clientId qaytadi.
     * @param consume true bo'lsa — bir martali: nonce ishlatiladi (qayta ishlatib bo'lmaydi).
     *                false bo'lsa — faqat imzo+muddat tekshiriladi (masalan arzon /check uchun).
     */
    public Optional<String> verify(String token, boolean consume) {
        if (token == null || token.isBlank()) return Optional.empty();
        int dot = token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1) return Optional.empty();

        String payload;
        try {
            payload = new String(Base64.getUrlDecoder().decode(token.substring(0, dot)), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        String sig = token.substring(dot + 1);

        // 1. Imzo to'g'rimi (constant-time)
        if (!constantTimeEquals(sig, b64(hmac(payload)))) return Optional.empty();

        // 2. Payload format: clientId | exp | nonce
        String[] f = payload.split("\\|", -1);
        if (f.length != 3) return Optional.empty();
        String clientId = f[0];
        long exp;
        try {
            exp = Long.parseLong(f[1]);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        String nonce = f[2];

        long now = System.currentTimeMillis();
        if (now > exp) return Optional.empty(); // muddati tugagan
        purgeExpired(now);

        // 3. Bir martali (faqat consume rejimida)
        if (consume && usedNonces.putIfAbsent(nonce, exp) != null) {
            return Optional.empty(); // allaqachon ishlatilgan
        }
        return Optional.of(clientId);
    }

    /** apiKey'dan ochiq (sir bo'lmagan) clientId — rate-limit/identifikatsiya uchun. */
    public String clientId(String apiKey) {
        return hex(sha256(apiKey)).substring(0, 16);
    }

    // ---- ichki ----

    private String randomNonce() {
        byte[] b = new byte[12];
        random.nextBytes(b);
        return b64(b);
    }

    private void purgeExpired(long now) {
        if (usedNonces.size() > 10_000) { // oddiy himoya
            usedNonces.entrySet().removeIf(e -> e.getValue() < now);
        }
    }

    private byte[] hmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(config.getTokenSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC xatosi", e);
        }
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String b64(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
        return sb.toString();
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
