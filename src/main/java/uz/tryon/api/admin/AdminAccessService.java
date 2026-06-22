package uz.tryon.api.admin;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import uz.tryon.api.auth.AuthService;
import uz.tryon.api.auth.Client;
import uz.tryon.api.auth.ClientRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Super-admin ruxsatini tekshiruvchi yordamchi.
 *
 * Rol HAR SAFAR DB dan o'qiladi (token ichidan emas) — tokenni qayta chiqarmasdan
 * rolni darhol o'zgartirish/bekor qilish imkonini beradi.
 *
 * Token yo'q/yaroqsiz   -> 401
 * Token yaroqli, ammo super-admin emas -> 403
 */
@Service
public class AdminAccessService {

    public static final String ROLE_SUPER_ADMIN = "SUPER_ADMIN";

    private final AuthService authService;
    private final ClientRepository clients;

    public AdminAccessService(AuthService authService, ClientRepository clients) {
        this.authService = authService;
        this.clients = clients;
    }

    /**
     * Sessiya tokenini tekshiradi va mijoz super-admin ekanini ta'minlaydi.
     * @return autentifikatsiyalangan super-admin Client.
     * @throws ResponseStatusException 401 (token yo'q/yaroqsiz) yoki 403 (super-admin emas).
     */
    public Client requireSuperAdmin(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sessiya tokeni xato yoki muddati o'tgan.");
        }
        Optional<String> clientIdOpt = authService.verifySessionToken(header.substring(7));
        if (clientIdOpt.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sessiya tokeni xato yoki muddati o'tgan.");
        }

        UUID clientId;
        try {
            clientId = UUID.fromString(clientIdOpt.get());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sessiya tokeni xato yoki muddati o'tgan.");
        }

        // Rol DB dan o'qiladi — token ichidagi ma'lumotga tayanmaymiz.
        Client c = clients.findById(clientId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "Sessiya tokeni xato yoki muddati o'tgan."));
        if (!ROLE_SUPER_ADMIN.equals(c.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Ruxsat yo'q: faqat super-admin uchun.");
        }
        return c;
    }
}
