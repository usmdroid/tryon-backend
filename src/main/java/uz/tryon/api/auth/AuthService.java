package uz.tryon.api.auth;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import uz.tryon.api.AppConfig;
import uz.tryon.api.util.AuthHashUtils;
import uz.tryon.api.wallet.CreditService;

import java.nio.charset.StandardCharsets;
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

    private static final long SESSION_TTL_MS = 7L * 24 * 60 * 60 * 1000;

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
    public static class AccountSuspendedException extends RuntimeException { }

    /** Telefon va email — ikkalasi ham majburiy va unik. (Bo'sh email controllerda rad etiladi.) */
    public Client register(String name, String phone, String email, String password) {
        String normPhone = Phones.normalize(phone);
        if (clients.existsByPhone(normPhone)) {
            throw new PhoneAlreadyExistsException();
        }
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
        if (!"ACTIVE".equals(c.getStatus())) {
            throw new AccountSuspendedException();
        }
        return c;
    }

    /** Dashboard sessiya tokeni (imzolangan, 7 kun). */
    public String issueSessionToken(Client c) {
        long exp = System.currentTimeMillis() + SESSION_TTL_MS;
        String payload = c.getId() + "|" + exp;
        return AuthHashUtils.b64Url(payload.getBytes(StandardCharsets.UTF_8))
                + "." + AuthHashUtils.b64Url(AuthHashUtils.hmacSha256(config.getTokenSecret(), payload));
    }

    /**
     * Tokenni tekshiradi; yaroqli bo'lsa clientId (UUID matni) qaytadi.
     * SUSPENDED tekshiruvi alohida SuspendedSessionFilter'da (403 + code:"SUSPENDED").
     * Bu yerda — faqat kriptografik token validligi.
     */
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
        if (!AuthHashUtils.constantTimeEquals(
                token.substring(dot + 1),
                AuthHashUtils.b64Url(AuthHashUtils.hmacSha256(config.getTokenSecret(), payload)))) {
            return Optional.empty();
        }
        String[] f = payload.split("\\|", -1);
        if (f.length != 2) return Optional.empty();
        try {
            if (System.currentTimeMillis() > Long.parseLong(f[1])) return Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        return Optional.of(f[0]);
    }
}
