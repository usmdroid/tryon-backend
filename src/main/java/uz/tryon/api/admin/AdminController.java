package uz.tryon.api.admin;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.tryon.api.auth.Client;
import uz.tryon.api.auth.ClientRepository;
import uz.tryon.api.auth.OtpService;
import uz.tryon.api.wallet.CreditService;
import uz.tryon.api.wallet.CreditTransaction;
import uz.tryon.api.wallet.CreditTransactionRepository;
import uz.tryon.api.wallet.Wallet;
import uz.tryon.api.wallet.WalletRepository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Super-admin paneli API'si (/api/admin/*).
 * Har bir endpoint avval requireSuperAdmin() ni chaqiradi:
 *   token yo'q/yaroqsiz -> 401, super-admin emas -> 403.
 * Auth: Authorization: Bearer <session-token>.
 *
 * Keyingi bosqich (HOZIR EMAS): admin uchun 2FA + IP allowlist + alohida subdomain.
 * Hozircha RBAC + tenant izolyatsiya to'g'ri qilingan, keyin subdomain'ga ajratish oson bo'lsin.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AdminAccessService access;
    private final AdminService adminService;
    private final ClientRepository clients;
    private final WalletRepository wallets;
    private final CreditTransactionRepository txRepo;
    private final CreditService creditService;
    private final OtpService otpService;

    public AdminController(AdminAccessService access, AdminService adminService,
                           ClientRepository clients, WalletRepository wallets,
                           CreditTransactionRepository txRepo, CreditService creditService,
                           OtpService otpService) {
        this.access = access;
        this.adminService = adminService;
        this.clients = clients;
        this.wallets = wallets;
        this.txRepo = txRepo;
        this.creditService = creditService;
        this.otpService = otpService;
    }

    public record CreditRequest(double amountSim) { }
    public record UnblockOtpRequest(String email) { }

    /** Barcha mijozlar ro'yxati (balans, so'rovlar, holat, rol). */
    @GetMapping("/clients")
    public ResponseEntity<?> listClients(HttpServletRequest request) {
        access.requireSuperAdmin(request);

        List<Map<String, Object>> result = clients.findAllByOrderByCreatedAtDesc().stream().map(c -> {
            Wallet w = wallets.findById(c.getId()).orElse(null);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId());
            m.put("name", c.getName());
            m.put("phone", c.getPhone());
            m.put("balanceSim", w == null ? 0.0 : w.getBalanceMsim() / 1000.0);
            m.put("totalRequests", w == null ? 0L : w.getTotalRequests());
            m.put("status", c.getStatus());
            m.put("role", c.getRole());
            m.put("createdAt", c.getCreatedAt());
            return m;
        }).toList();
        return ResponseEntity.ok(result);
    }

    /** Bitta mijoz tafsilotlari + oxirgi tranzaksiyalar. 404 — topilmasa. */
    @GetMapping("/clients/{id}")
    public ResponseEntity<?> clientDetail(@PathVariable UUID id, HttpServletRequest request) {
        access.requireSuperAdmin(request);

        Optional<Client> opt = clients.findById(id);
        if (opt.isEmpty()) return notFound();
        Client c = opt.get();

        Wallet w = wallets.findById(id).orElse(null);
        long balanceMsim = w == null ? 0L : w.getBalanceMsim();
        long totalRequests = w == null ? 0L : w.getTotalRequests();
        double totalSpentSim = txRepo.sumDebitMsim(id) / 1000.0;

        List<Map<String, Object>> transactions = creditService.getTransactions(id, 50).stream().map(tx -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", tx.getId());
            m.put("amountSim", tx.getAmountMsim() / 1000.0);
            m.put("type", tx.getType());
            m.put("balanceAfterSim", tx.getBalanceAfterMsim() / 1000.0);
            m.put("meta", tx.getMeta());
            m.put("createdAt", tx.getCreatedAt());
            return m;
        }).toList();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", c.getId());
        body.put("name", c.getName());
        body.put("phone", c.getPhone());
        body.put("email", c.getEmail());
        body.put("role", c.getRole());
        body.put("status", c.getStatus());
        body.put("createdAt", c.getCreatedAt());
        body.put("balanceSim", balanceMsim / 1000.0);
        body.put("totalRequests", totalRequests);
        body.put("totalSpentSim", totalSpentSim);
        body.put("transactions", transactions);
        return ResponseEntity.ok(body);
    }

    /** Mijozga qo'lda kredit qo'shish (sim). ADMIN_CREDIT qatori yoziladi. 404 — topilmasa. */
    @PostMapping("/clients/{id}/credit")
    public ResponseEntity<?> credit(@PathVariable UUID id, @RequestBody CreditRequest req,
                                    HttpServletRequest request) {
        access.requireSuperAdmin(request);

        if (clients.findById(id).isEmpty()) return notFound();
        if (req.amountSim() <= 0) {
            return err(HttpStatus.BAD_REQUEST, "amountSim musbat bo'lishi kerak.");
        }

        Wallet w = creditService.adminCreditSim(id, req.amountSim());
        return ResponseEntity.ok(Map.of("balanceSim", w.getBalanceMsim() / 1000.0));
    }

    /** Mijozni to'xtatish (SUSPENDED). 404 — topilmasa. */
    @PostMapping("/clients/{id}/suspend")
    public ResponseEntity<?> suspend(@PathVariable UUID id, HttpServletRequest request) {
        access.requireSuperAdmin(request);
        if (adminService.suspend(id).isEmpty()) return notFound();
        return ResponseEntity.ok(Map.of("status", "SUSPENDED"));
    }

    /** Mijozni faollashtirish (ACTIVE). 404 — topilmasa. */
    @PostMapping("/clients/{id}/activate")
    public ResponseEntity<?> activate(@PathVariable UUID id, HttpServletRequest request) {
        access.requireSuperAdmin(request);
        if (adminService.activate(id).isEmpty()) return notFound();
        return ResponseEntity.ok(Map.of("status", "ACTIVE"));
    }

    /** Umumiy statistika: mijozlar soni, jami so'rovlar (debit), jami tushum (PURCHASE). */
    @GetMapping("/stats")
    public ResponseEntity<?> stats(HttpServletRequest request) {
        access.requireSuperAdmin(request);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("totalClients", clients.count());
        body.put("totalRequests", txRepo.countAllDebits());
        body.put("totalRevenueSim", txRepo.sumAllPurchaseMsim() / 1000.0);
        return ResponseEntity.ok(body);
    }

    /** OTP suiiste'mol blokini bekor qilish — blok + breach + eskalatsiya darajasini tozalaydi. */
    @PostMapping("/otp/unblock")
    public ResponseEntity<?> unblockOtp(@RequestBody UnblockOtpRequest req, HttpServletRequest request) {
        access.requireSuperAdmin(request);
        if (req == null || req.email() == null || req.email().isBlank()) {
            return err(HttpStatus.BAD_REQUEST, "Email kiritilishi shart.");
        }
        otpService.unblock(req.email());
        return ResponseEntity.ok(Map.of("message", "OTP bloki bekor qilindi."));
    }

    private ResponseEntity<Map<String, String>> notFound() {
        return err(HttpStatus.NOT_FOUND, "Mijoz topilmadi.");
    }

    private ResponseEntity<Map<String, String>> err(HttpStatus status, String message) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", message));
    }
}
