package uz.tryon.api.util;

import io.sentry.protocol.User;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import uz.tryon.api.TokenService;
import uz.tryon.api.auth.AuthService;

/**
 * Sentry hodisalariga faqat clientId (UUID) biriktiradi — boshqa hech qanday
 * shaxsiy ma'lumot (ism, email, telefon, IP, token) yuborilmaydi.
 * Token yo'q/yaroqsiz bo'lsa null qaytaradi va hech qachon exception otmaydi.
 */
@Component
public class SentryUserProvider implements io.sentry.spring.jakarta.SentryUserProvider {

    private final AuthService authService;
    private final TokenService tokenService;

    public SentryUserProvider(AuthService authService, TokenService tokenService) {
        this.authService = authService;
        this.tokenService = tokenService;
    }

    @Override
    public User provideUser() {
        try {
            if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) {
                return null;
            }
            HttpServletRequest request = attrs.getRequest();
            return BearerExtractor.extract(request)
                    // Dashboard sessiya tokeni; bo'lmasa widget tokeni (nonce ISHLATILMAYDI — consume=false).
                    .flatMap(token -> authService.verifySessionToken(token)
                            .or(() -> tokenService.verify(token, false)))
                    .map(clientId -> {
                        User user = new User();
                        user.setId(clientId);
                        return user;
                    })
                    .orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
