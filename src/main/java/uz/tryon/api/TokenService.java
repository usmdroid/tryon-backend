package uz.tryon.api;

import org.springframework.stereotype.Service;
import uz.tryon.api.nonce.NonceStore;
import uz.tryon.api.util.AuthHashUtils;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

/**
 * Sessiya tokeni — do'kon serveri {@code /api/session} orqali oladi, brauzer esa
 * {@code /api/tryon} ga {@code Authorization: Bearer <token>} bilan keladi.
 *
 * Token: HMAC-SHA256 bilan imzolangan, qisqa muddatli (TTL) va bir martali (nonce).
 * Format: base64url(payload) + "." + base64url(hmac(payload))
 *   payload = clientId | expEpochMs | nonce | apiKeyId (ixtiyoriy)
 *   clientId = apiKey'ning xeshi (secret kalit token ichiga tushmaydi).
 *
 * Ishlatilgan nonce'lar NonceStore orqali saqlanadi (in-memory yoki Redis).
 */
@Service
public class TokenService {

    private final AppConfig config;
    private final NonceStore nonceStore;
    private final SecureRandom random = new SecureRandom();

    public TokenService(AppConfig config, NonceStore nonceStore) {
        this.config = config;
        this.nonceStore = nonceStore;
    }

    public record Issued(String token, long expiresInSeconds) {}

    /** Tekshiruv natijasi: clientId + (ixtiyoriy) so'rovni keltirgan API kalit id'si. */
    public record Verified(String clientId, String apiKeyId) {}

    /** Subject uchun token zarb qiladi (API kalit id'siz). */
    public Issued mint(String subject) {
        return mint(subject, null);
    }

    /**
     * Subject (clientId) + API kalit id (nullable) uchun token zarb qiladi.
     * apiKeyId token ichiga kiritiladi, shunda /tryon paytida foydalanish kalitga bog'lanadi.
     */
    public Issued mint(String subject, String apiKeyId) {
        long ttl = config.getTokenTtlSeconds();
        long exp = System.currentTimeMillis() + ttl * 1000;
        String payload = subject + "|" + exp + "|" + randomNonce() + "|" + (apiKeyId == null ? "" : apiKeyId);
        String token = AuthHashUtils.b64Url(payload.getBytes(StandardCharsets.UTF_8))
                + "." + AuthHashUtils.b64Url(AuthHashUtils.hmacSha256(config.getTokenSecret(), payload));
        return new Issued(token, ttl);
    }

    /**
     * Tokenni tekshiradi. Yaroqli bo'lsa clientId qaytaradi.
     * @param consume true bo'lsa — bir martali: nonce ishlatiladi (qayta ishlatib bo'lmaydi).
     */
    public Optional<String> verify(String token, boolean consume) {
        return verifyDetailed(token, consume).map(Verified::clientId);
    }

    /**
     * Tokenni tekshiradi va clientId bilan birga (mavjud bo'lsa) API kalit id'sini qaytaradi.
     * Eski 3-maydonli tokenlar ham qabul qilinadi (apiKeyId = null).
     */
    public Optional<Verified> verifyDetailed(String token, boolean consume) {
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

        if (!AuthHashUtils.constantTimeEquals(
                sig, AuthHashUtils.b64Url(AuthHashUtils.hmacSha256(config.getTokenSecret(), payload)))) {
            return Optional.empty();
        }

        String[] f = payload.split("\\|", -1);
        if (f.length != 3 && f.length != 4) return Optional.empty();
        String clientId = f[0];
        long exp;
        try {
            exp = Long.parseLong(f[1]);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        String nonce = f[2];
        String apiKeyId = (f.length == 4 && !f[3].isBlank()) ? f[3] : null;

        long now = System.currentTimeMillis();
        if (now > exp) return Optional.empty();

        if (consume && !nonceStore.tryConsume(nonce, exp)) {
            return Optional.empty();
        }
        return Optional.of(new Verified(clientId, apiKeyId));
    }

    /** apiKey'dan ochiq (sir bo'lmagan) clientId — rate-limit/identifikatsiya uchun. */
    public String clientId(String apiKey) {
        return AuthHashUtils.hex(AuthHashUtils.sha256(apiKey)).substring(0, 16);
    }

    private String randomNonce() {
        byte[] b = new byte[12];
        random.nextBytes(b);
        return AuthHashUtils.b64Url(b);
    }
}
