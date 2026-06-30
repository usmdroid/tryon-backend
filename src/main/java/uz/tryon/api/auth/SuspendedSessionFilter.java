package uz.tryon.api.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

/**
 * Bearer token bilan kirayotgan har so'rovni tekshiradi:
 * agar token yaroqli (HMAC OK), lekin akkaunt status != ACTIVE bo'lsa,
 * darhol 403 + {"error","code":"SUSPENDED"} qaytariladi.
 *
 * Faqat token validligi tekshiruvi endpoint'da qoladi (401).
 * Bu ajratish frontend'ga "bloklangan" va "token eskirgan" holatlarini farqlashga imkon beradi.
 */
@Component
public class SuspendedSessionFilter extends OncePerRequestFilter {

    private final AuthService authService;
    private final ClientRepository clients;

    public SuspendedSessionFilter(AuthService authService, ClientRepository clients) {
        this.authService = authService;
        this.clients = clients;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String header = req.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            Optional<String> clientIdOpt = authService.verifySessionToken(header.substring(7));
            if (clientIdOpt.isPresent()) {
                try {
                    UUID id = UUID.fromString(clientIdOpt.get());
                    Optional<Client> c = clients.findById(id);
                    if (c.isPresent() && !"ACTIVE".equals(c.get().getStatus())) {
                        res.setStatus(HttpStatus.FORBIDDEN.value());
                        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
                        res.getWriter().write(
                                "{\"error\":\"Akkauntingiz bloklangan. Iltimos, admin bilan bog'laning.\","
                                        + "\"code\":\"SUSPENDED\"}"
                        );
                        return;
                    }
                } catch (IllegalArgumentException ignored) {
                    // Yaroqsiz UUID — endpoint o'zi 401 qaytaradi.
                }
            }
        }
        chain.doFilter(req, res);
    }
}
