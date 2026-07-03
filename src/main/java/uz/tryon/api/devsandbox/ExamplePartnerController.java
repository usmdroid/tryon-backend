package uz.tryon.api.devsandbox;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import uz.tryon.api.RateLimiterService;
import uz.tryon.api.TokenService;

import java.util.Map;
import java.util.Optional;

/**
 * Public try-on token endpoint for developer sandbox.
 * No server-to-server secret needed — the dev key cap is the protection.
 *
 * GET/POST /api/example/partner/{devKey}
 *   - Validates key (exists, not revoked, used_count < max_count)
 *   - Rate-limits by client IP
 *   - Mints a dev session token (encodes "dev:{devKeyId}" as subject)
 *   - Returns { "token": "..." }
 *
 * Does NOT decrement used_count here — decrement happens in the try-on path.
 */
@RestController
@RequestMapping("/api/example/partner")
public class ExamplePartnerController {

    private final DevSandboxKeyService devKeyService;
    private final TokenService tokenService;
    private final RateLimiterService rateLimiter;

    public ExamplePartnerController(DevSandboxKeyService devKeyService,
                                    TokenService tokenService,
                                    RateLimiterService rateLimiter) {
        this.devKeyService = devKeyService;
        this.tokenService = tokenService;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping("/{devKey}")
    public ResponseEntity<?> getToken(@PathVariable String devKey, HttpServletRequest request) {
        return issueToken(devKey, request);
    }

    @PostMapping("/{devKey}")
    public ResponseEntity<?> postToken(@PathVariable String devKey, HttpServletRequest request) {
        return issueToken(devKey, request);
    }

    private ResponseEntity<?> issueToken(String devKey, HttpServletRequest request) {
        String ip = resolveIp(request);

        // Rate limit by IP
        if (!rateLimiter.allowDevSandboxIp(ip)) {
            return err(HttpStatus.TOO_MANY_REQUESTS,
                    "Juda ko'p so'rov. Bir daqiqadan keyin urinib ko'ring.");
        }

        Optional<DevSandboxKey> opt = devKeyService.findByKey(devKey);
        if (opt.isEmpty()) {
            return err(HttpStatus.NOT_FOUND, "dev limit tugadi, yangi kalit oling");
        }

        DevSandboxKey k = opt.get();
        if (k.getRevokedAt() != null) {
            return err(HttpStatus.GONE, "dev limit tugadi, yangi kalit oling");
        }
        if (k.getUsedCount() >= k.getMaxCount()) {
            return err(HttpStatus.PAYMENT_REQUIRED, "dev limit tugadi, yangi kalit oling");
        }

        // Mint token with dev context encoded as subject
        TokenService.Issued issued = tokenService.mint("dev:" + k.getId().toString(), null);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("token", issued.token(), "expiresIn", issued.expiresInSeconds()));
    }

    private static String resolveIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            // Take the LAST entry — nginx appends the real client IP as the final hop.
            // First entries are attacker-controlled and must not be trusted for rate limiting.
            String[] hops = forwarded.split(",");
            return hops[hops.length - 1].trim();
        }
        return request.getRemoteAddr();
    }

    private ResponseEntity<Map<String, String>> err(HttpStatus status, String message) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("message", message));
    }
}
