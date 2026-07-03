package uz.tryon.api.admin;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import uz.tryon.api.auth.Client;
import uz.tryon.api.util.ApiErrors;

import java.util.Map;

/**
 * SUPER_ADMIN-only endpoints for the TRYON_ENABLED kill switch.
 * MODERATOR and CLIENT receive 403 from requireSuperAdmin.
 */
@RestController
@RequestMapping("/api/admin")
public class TryonFlagController {

    private final AdminAccessService access;
    private final TryonFlagService flagService;

    public TryonFlagController(AdminAccessService access, TryonFlagService flagService) {
        this.access = access;
        this.flagService = flagService;
    }

    public record ToggleRequest(boolean enabled, String password) {}

    /** GET /api/admin/tryon-flag — returns current flag status. SUPER_ADMIN only. */
    @GetMapping("/tryon-flag")
    public ResponseEntity<?> getFlag(HttpServletRequest request) {
        access.requireSuperAdmin(request);
        return ResponseEntity.ok(Map.of("enabled", flagService.isEnabled()));
    }

    /**
     * POST /api/admin/tryon-flag — toggles the flag after step-up password re-auth.
     * Wrong password => 403, flag unchanged. Password is never logged or returned.
     */
    @PostMapping("/tryon-flag")
    public ResponseEntity<?> setFlag(@RequestBody ToggleRequest req, HttpServletRequest request) {
        Client admin = access.requireSuperAdmin(request);
        if (req == null || req.password() == null || req.password().isBlank()) {
            return ApiErrors.err(HttpStatus.BAD_REQUEST, "Parol kiritilishi shart.");
        }
        try {
            boolean newValue = flagService.toggle(admin, req.enabled(), req.password());
            return ResponseEntity.ok(Map.of("enabled", newValue));
        } catch (TryonFlagService.WrongPasswordException e) {
            return ApiErrors.err(HttpStatus.FORBIDDEN, "Parol noto'g'ri. O'zgarish qabul qilinmadi.");
        }
    }
}
