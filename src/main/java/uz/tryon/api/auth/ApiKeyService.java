package uz.tryon.api.auth;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Service
public class ApiKeyService {

    private final ApiKeyRepository repo;
    private final SecretCipher cipher;

    public ApiKeyService(ApiKeyRepository repo, SecretCipher cipher) {
        this.repo = repo;
        this.cipher = cipher;
    }

    public static class NotFoundException extends RuntimeException { }

    public record CreateResult(ApiKey key, String secret) { }

    /**
     * Yangi API kalit yaratadi. To'liq kalit shifrlangan holda saqlanadi (keyin ham nusxalash uchun).
     * @param publishable true → pk_ (brauzer, domen allowlist bilan); false → sk_ (server).
     * @param allowedDomains pk_ uchun ruxsat etilgan domenlar (vergul bilan); sk_ uchun e'tiborga olinmaydi.
     */
    public CreateResult create(UUID clientId, String name, boolean publishable, String allowedDomains) {
        byte[] randomBytes = new byte[32];
        new SecureRandom().nextBytes(randomBytes);
        String prefix = publishable ? "pk_" : "sk_";
        String secret = prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        String keyPrefix = secret.substring(0, Math.min(12, secret.length()));
        String keyHash = sha256hex(secret);
        String type = publishable ? "publishable" : "secret";
        String domains = publishable ? allowedDomains : null;
        ApiKey key = repo.save(new ApiKey(clientId, name, keyPrefix, keyHash, cipher.encrypt(secret), type, domains));
        return new CreateResult(key, secret);
    }

    /** To'liq kalitni ochib beradi (eski/buzilgan kalitlarda null). */
    public String revealKey(ApiKey key) {
        return cipher.decrypt(key.getKeyEnc());
    }

    public List<ApiKey> listByClient(UUID clientId) {
        return repo.findByClientIdOrderByCreatedAtDesc(clientId);
    }

    /** Kalitni bekor qiladi (idempotent). Kalit boshqa clientga tegishli bo'lsa NotFoundException. */
    @Transactional
    public ApiKey revoke(UUID id, UUID clientId) {
        ApiKey key = repo.findByIdAndClientId(id, clientId)
                .orElseThrow(NotFoundException::new);
        key.revoke();
        return repo.save(key);
    }

    private static String sha256hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
