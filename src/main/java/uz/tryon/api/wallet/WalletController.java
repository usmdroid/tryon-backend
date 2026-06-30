package uz.tryon.api.wallet;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.tryon.api.auth.AuthService;
import uz.tryon.api.util.BearerExtractor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Hamyon boshqaruvi (dashboard uchun).
 *   GET  /api/wallet                     — balans
 *   GET  /api/wallet/transactions?limit= — tranzaksiyalar
 *   POST /api/wallet/purchase { amountUsd } — sim sotib olish (STUB)
 * Auth: Authorization: Bearer <session-token>
 */
@RestController
@RequestMapping("/api/wallet")
public class WalletController {

    private final AuthService authService;
    private final CreditService creditService;

    public WalletController(AuthService authService, CreditService creditService) {
        this.authService = authService;
        this.creditService = creditService;
    }

    public record PurchaseRequest(double amountUsd) { }

    @GetMapping
    public ResponseEntity<?> getWallet(HttpServletRequest request) {
        Optional<String> clientIdOpt = authenticate(request);
        if (clientIdOpt.isEmpty()) return unauthorized();

        UUID clientId = UUID.fromString(clientIdOpt.get());
        Wallet w = creditService.getWallet(clientId);
        return ResponseEntity.ok(walletBody(w));
    }

    @GetMapping("/transactions")
    public ResponseEntity<?> getTransactions(
            HttpServletRequest request,
            @RequestParam(defaultValue = "50") int limit) {
        Optional<String> clientIdOpt = authenticate(request);
        if (clientIdOpt.isEmpty()) return unauthorized();

        UUID clientId = UUID.fromString(clientIdOpt.get());
        int safeLimit = Math.max(1, Math.min(limit, 200));
        List<Map<String, Object>> result = creditService.getTransactions(clientId, safeLimit).stream()
                .map(tx -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", tx.getId());
                    m.put("amountSim", tx.getAmountMsim() / 1000.0);
                    m.put("type", tx.getType());
                    m.put("balanceAfterSim", tx.getBalanceAfterMsim() / 1000.0);
                    m.put("createdAt", tx.getCreatedAt());
                    return m;
                }).toList();
        return ResponseEntity.ok(result);
    }

    @PostMapping("/purchase")
    public ResponseEntity<?> purchase(HttpServletRequest request, @RequestBody PurchaseRequest req) {
        Optional<String> clientIdOpt = authenticate(request);
        if (clientIdOpt.isEmpty()) return unauthorized();
        if (req.amountUsd() <= 0) {
            return err(HttpStatus.BAD_REQUEST, "amountUsd musbat bo'lishi kerak.");
        }

        UUID clientId = UUID.fromString(clientIdOpt.get());
        Wallet w = creditService.purchase(clientId, req.amountUsd());
        return ResponseEntity.ok(walletBody(w));
    }

    private Map<String, Object> walletBody(Wallet w) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("balanceSim", w.getBalanceMsim() / 1000.0);
        m.put("balanceMsim", w.getBalanceMsim());
        m.put("totalRequests", w.getTotalRequests());
        m.put("freeGrantSim", 100);
        return m;
    }

    private Optional<String> authenticate(HttpServletRequest req) {
        return BearerExtractor.extract(req).flatMap(authService::verifySessionToken);
    }

    private ResponseEntity<Map<String, String>> unauthorized() {
        return err(HttpStatus.UNAUTHORIZED, "Sessiya tokeni xato yoki muddati o'tgan.");
    }

    private ResponseEntity<Map<String, String>> err(HttpStatus status, String message) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", message));
    }
}
