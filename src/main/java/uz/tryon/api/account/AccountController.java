package uz.tryon.api.account;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import uz.tryon.api.auth.AuthService;
import uz.tryon.api.auth.OtpService;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Akkaunt sozlamalari: telefon/email o'zgartirish, qo'shimcha emaillar.
 * Barcha endpointlar: Authorization: Bearer <token>
 */
@RestController
@RequestMapping("/api/account")
public class AccountController {

    private final AuthService authService;
    private final AccountService accountService;

    public AccountController(AuthService authService, AccountService accountService) {
        this.authService = authService;
        this.accountService = accountService;
    }

    public record PhoneChangeRequest(String newPhone) { }
    public record PhoneVerifyRequest(String code, String newPhone) { }
    public record EmailChangeRequest(String newEmail) { }
    public record EmailVerifyRequest(String code, String newEmail) { }
    public record AddSecondaryEmailRequest(String email) { }
    public record VerifySecondaryEmailRequest(String code, String email) { }

    /** POST /api/account/phone/change-request */
    @PostMapping("/phone/change-request")
    public ResponseEntity<?> phoneChangeRequest(HttpServletRequest request,
                                                @RequestBody PhoneChangeRequest req) {
        Optional<String> clientIdOpt = authenticate(request);
        if (clientIdOpt.isEmpty()) return unauthorized();
        if (isBlank(req.newPhone())) return err(HttpStatus.BAD_REQUEST, "newPhone kiritilishi shart.");

        UUID clientId = UUID.fromString(clientIdOpt.get());
        try {
            return ResponseEntity.ok(accountService.requestPhoneChange(clientId, req.newPhone()));
        } catch (AccountService.ValidationException e) {
            return err(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (AccountService.ConflictException e) {
            return err(HttpStatus.CONFLICT, e.getMessage());
        } catch (OtpService.TooSoonException e) {
            return err(HttpStatus.TOO_MANY_REQUESTS, "Kod yaqinda yuborilgan. Biroz kuting.");
        } catch (OtpService.BlockedException e) {
            return err(HttpStatus.TOO_MANY_REQUESTS, blockedMessage(e));
        }
    }

    /** POST /api/account/phone/verify */
    @PostMapping("/phone/verify")
    public ResponseEntity<?> phoneVerify(HttpServletRequest request,
                                         @RequestBody PhoneVerifyRequest req) {
        Optional<String> clientIdOpt = authenticate(request);
        if (clientIdOpt.isEmpty()) return unauthorized();
        if (isBlank(req.code()) || isBlank(req.newPhone())) {
            return err(HttpStatus.BAD_REQUEST, "code va newPhone kiritilishi shart.");
        }

        UUID clientId = UUID.fromString(clientIdOpt.get());
        try {
            return ResponseEntity.ok(accountService.verifyPhoneChange(clientId, req.code(), req.newPhone()));
        } catch (AccountService.ValidationException e) {
            return err(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (AccountService.ConflictException e) {
            return err(HttpStatus.CONFLICT, e.getMessage());
        } catch (OtpService.BlockedException e) {
            return err(HttpStatus.TOO_MANY_REQUESTS, blockedMessage(e));
        }
    }

    /** POST /api/account/email/change-request */
    @PostMapping("/email/change-request")
    public ResponseEntity<?> emailChangeRequest(HttpServletRequest request,
                                                @RequestBody EmailChangeRequest req) {
        Optional<String> clientIdOpt = authenticate(request);
        if (clientIdOpt.isEmpty()) return unauthorized();
        if (isBlank(req.newEmail())) return err(HttpStatus.BAD_REQUEST, "newEmail kiritilishi shart.");

        UUID clientId = UUID.fromString(clientIdOpt.get());
        try {
            return ResponseEntity.ok(accountService.requestEmailChange(clientId, req.newEmail()));
        } catch (AccountService.ValidationException e) {
            return err(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (AccountService.ConflictException e) {
            return err(HttpStatus.CONFLICT, e.getMessage());
        } catch (OtpService.TooSoonException e) {
            return err(HttpStatus.TOO_MANY_REQUESTS, "Kod yaqinda yuborilgan. Biroz kuting.");
        } catch (OtpService.BlockedException e) {
            return err(HttpStatus.TOO_MANY_REQUESTS, blockedMessage(e));
        }
    }

    /** POST /api/account/email/verify */
    @PostMapping("/email/verify")
    public ResponseEntity<?> emailVerify(HttpServletRequest request,
                                         @RequestBody EmailVerifyRequest req) {
        Optional<String> clientIdOpt = authenticate(request);
        if (clientIdOpt.isEmpty()) return unauthorized();
        if (isBlank(req.code()) || isBlank(req.newEmail())) {
            return err(HttpStatus.BAD_REQUEST, "code va newEmail kiritilishi shart.");
        }

        UUID clientId = UUID.fromString(clientIdOpt.get());
        try {
            return ResponseEntity.ok(accountService.verifyEmailChange(clientId, req.code(), req.newEmail()));
        } catch (AccountService.ValidationException e) {
            return err(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (AccountService.ConflictException e) {
            return err(HttpStatus.CONFLICT, e.getMessage());
        } catch (OtpService.BlockedException e) {
            return err(HttpStatus.TOO_MANY_REQUESTS, blockedMessage(e));
        }
    }

    /** GET /api/account/email/secondary */
    @GetMapping("/email/secondary")
    public ResponseEntity<?> listSecondaryEmails(HttpServletRequest request) {
        Optional<String> clientIdOpt = authenticate(request);
        if (clientIdOpt.isEmpty()) return unauthorized();

        UUID clientId = UUID.fromString(clientIdOpt.get());
        return ResponseEntity.ok(accountService.getSecondaryEmails(clientId));
    }

    /** POST /api/account/email/add */
    @PostMapping("/email/add")
    public ResponseEntity<?> addSecondaryEmail(HttpServletRequest request,
                                               @RequestBody AddSecondaryEmailRequest req) {
        Optional<String> clientIdOpt = authenticate(request);
        if (clientIdOpt.isEmpty()) return unauthorized();
        if (isBlank(req.email())) return err(HttpStatus.BAD_REQUEST, "email kiritilishi shart.");

        UUID clientId = UUID.fromString(clientIdOpt.get());
        try {
            return ResponseEntity.ok(accountService.addSecondaryEmail(clientId, req.email()));
        } catch (AccountService.ValidationException e) {
            return err(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (AccountService.ConflictException e) {
            return err(HttpStatus.CONFLICT, e.getMessage());
        } catch (OtpService.TooSoonException e) {
            return err(HttpStatus.TOO_MANY_REQUESTS, "Kod yaqinda yuborilgan. Biroz kuting.");
        } catch (OtpService.BlockedException e) {
            return err(HttpStatus.TOO_MANY_REQUESTS, blockedMessage(e));
        }
    }

    /** POST /api/account/email/verify-secondary */
    @PostMapping("/email/verify-secondary")
    public ResponseEntity<?> verifySecondaryEmail(HttpServletRequest request,
                                                  @RequestBody VerifySecondaryEmailRequest req) {
        Optional<String> clientIdOpt = authenticate(request);
        if (clientIdOpt.isEmpty()) return unauthorized();
        if (isBlank(req.code()) || isBlank(req.email())) {
            return err(HttpStatus.BAD_REQUEST, "code va email kiritilishi shart.");
        }

        UUID clientId = UUID.fromString(clientIdOpt.get());
        try {
            return ResponseEntity.ok(accountService.verifySecondaryEmail(clientId, req.code(), req.email()));
        } catch (AccountService.ValidationException e) {
            return err(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (AccountService.ConflictException e) {
            return err(HttpStatus.CONFLICT, e.getMessage());
        } catch (AccountService.NotFoundException e) {
            return err(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (OtpService.BlockedException e) {
            return err(HttpStatus.TOO_MANY_REQUESTS, blockedMessage(e));
        }
    }

    /** DELETE /api/account/email/{id} */
    @DeleteMapping("/email/{id}")
    public ResponseEntity<?> deleteSecondaryEmail(HttpServletRequest request,
                                                   @PathVariable UUID id) {
        Optional<String> clientIdOpt = authenticate(request);
        if (clientIdOpt.isEmpty()) return unauthorized();

        UUID clientId = UUID.fromString(clientIdOpt.get());
        try {
            accountService.deleteSecondaryEmail(clientId, id);
            return ResponseEntity.noContent().build();
        } catch (AccountService.NotFoundException e) {
            return err(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    private Optional<String> authenticate(HttpServletRequest req) {
        String header = req.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) return Optional.empty();
        return authService.verifySessionToken(header.substring(7));
    }

    private ResponseEntity<Map<String, String>> unauthorized() {
        return err(HttpStatus.UNAUTHORIZED, "Sessiya tokeni xato yoki muddati o'tgan.");
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String blockedMessage(OtpService.BlockedException e) {
        long secs = e.getRemainingSeconds();
        if (secs >= 60) {
            long mins = (secs + 59) / 60;
            return "Juda ko'p urinish. " + mins + " daqiqadan so'ng qayta urinib ko'ring.";
        }
        return "Juda ko'p urinish. " + secs + " soniyadan so'ng qayta urinib ko'ring.";
    }

    private ResponseEntity<Map<String, String>> err(HttpStatus status, String message) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", message));
    }
}
