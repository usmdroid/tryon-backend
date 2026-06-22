package uz.tryon.api.auth;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import uz.tryon.api.AppConfig;
import uz.tryon.api.wallet.CreditService;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Optional;

/**
 * Hamkor akkauntlari: ro'yxatdan o'tish / kirish + dashboard sessiya tokeni.
 *
 * Sessiya tokeni — HMAC-SHA256 imzolangan, ko'p martalik (login sessiyasi),
 * uzoqroq muddatli (default 7 kun). Format: base64url(clientId|exp).base64url(hmac).
 * (Bu widget'ning bir martali tokenidan farq qiladi — u TokenService'da.)
 */
@Service
public class AuthService {

    private static final long SESSION_TTL_MS = 7L * 24 * 60 * 60 * 1000; // 7 kun

    private final ClientRepository clients;
    private final AppConfig config;
    private final CreditService creditService;
    private final PasswordEncoder encoder = new BCryptPasswordEncoder();

    public AuthService(ClientRepository clients, AppConfig config, CreditService creditService) {
        this.clients = clients;
        this.config = config;
        this.creditService = creditService;
    }

    public static class EmailAlreadyExistsException extends RuntimeException { }
    public static class PhoneAlreadyExistsException extends RuntimeException { }
    public static class InvalidCredentialsException extends RuntimeException { }

    /** Telefon va email — ikkalasi ham majburiy va unik. (Bo'sh email controllerda rad etiladi.) */
    public Client register(String name, String phone, String email, String password) {
        String normPhone = Phones.normalize(phone);
        if (clients.existsByPhone(normPhone)) {
            throw new PhoneAlreadyExistsException();
        }
        // Email har doim mavjud bo'ladi; mudofaa uchun bo'shni hamon null deb qoldiramiz.
        String normEmail = (email == null || email.isBlank()) ? null : email.trim().toLowerCase();
        if (normEmail != null && clients.existsByEmail(normEmail)) {
            throw new EmailAlreadyExistsException();
        }
        Client saved = clients.save(new Client(name.trim(), normPhone, normEmail, encoder.encode(password)));
        creditService.grantFree(saved.getId());
        return saved;
    }

    /** identifier — email yoki telefon. */
    public Client login(String identifier, String password) {
        String id = identifier.trim();
        Client c = clients.findByEmailOrPhone(id.toLowerCase(), Phones.normalize(id))
                .orElseThrow(InvalidCredentialsException::new);
        if (!encoder.matches(password, c.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }
        return c;
    }

    /** Dashboard sessiya tokeni (imzolangan, 7 kun). */
    public String issueSessionToken(Client c) {
        long exp = System.currentTimeMillis() + SESSION_TTL_MS;
        String payload = c.getId() + "|" + exp;
        return b64(payload.getBytes(StandardCharsets.UTF_8)) + "." + b64(hmac(payload));
    }

    /** Tokenni tekshiradi; yaroqli bo'lsa clientId (UUID matni) qaytadi. */
    public Optional<String> verifySessionToken(String token) {
        if (token == null) return Optional.empty();
        int dot = token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1) return Optional.empty();
        String payload;
        try {
            payload = new String(Base64.getUrlDecoder().decode(token.substring(0, dot)), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (!constantTimeEquals(token.substring(dot + 1), b64(hmac(payload)))) return Optional.empty();
        String[] f = payload.split("\\|", -1);
        if (f.length != 2) return Optional.empty();
        try {
            if (System.currentTimeMillis() > Long.parseLong(f[1])) return Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        return Optional.of(f[0]);
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

    private static String b64(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
