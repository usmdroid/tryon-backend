package uz.tryon.api.account;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.tryon.api.AppConfig;
import uz.tryon.api.auth.Client;
import uz.tryon.api.auth.ClientRepository;
import uz.tryon.api.auth.OtpService;
import uz.tryon.api.auth.Phones;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class AccountService {

    private static final Pattern EMAIL_RX = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    // +998 followed by exactly 9 digits
    private static final Pattern PHONE_RX = Pattern.compile("^\\+998\\d{9}$");

    private final ClientRepository clientRepo;
    private final SecondaryEmailRepository secondaryEmailRepo;
    private final AccountAuditLogRepository auditRepo;
    private final OtpService otpService;
    private final AppConfig config;

    public AccountService(
            ClientRepository clientRepo,
            SecondaryEmailRepository secondaryEmailRepo,
            AccountAuditLogRepository auditRepo,
            OtpService otpService,
            AppConfig config) {
        this.clientRepo = clientRepo;
        this.secondaryEmailRepo = secondaryEmailRepo;
        this.auditRepo = auditRepo;
        this.otpService = otpService;
        this.config = config;
    }

    public static class ValidationException extends RuntimeException {
        public ValidationException(String message) { super(message); }
    }

    public static class ConflictException extends RuntimeException {
        public ConflictException(String message) { super(message); }
    }

    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) { super(message); }
    }

    /**
     * POST /phone/change-request — OTP sent to primary email.
     * No SMS provider exists; primary email is the only secure OTP channel for phone changes.
     */
    public Map<String, Object> requestPhoneChange(UUID clientId, String newPhone) {
        if (newPhone == null || !PHONE_RX.matcher(newPhone.trim()).matches()) {
            throw new ValidationException("Telefon raqami +998XXXXXXXXX formatida bo'lishi kerak.");
        }
        String normPhone = Phones.normalize(newPhone);

        Client client = clientRepo.findById(clientId)
                .orElseThrow(() -> new NotFoundException("Foydalanuvchi topilmadi."));

        if (normPhone.equals(client.getPhone())) {
            throw new ValidationException("Bu allaqachon sizning telefon raqamingiz.");
        }
        if (clientRepo.existsByPhone(normPhone)) {
            throw new ConflictException("Bu telefon raqam allaqachon boshqa akkauntda ishlatilmoqda.");
        }

        // OTP is sent to the account's current primary email — no SMS provider exists.
        String primaryEmail = client.getEmail();
        if (primaryEmail == null || primaryEmail.isBlank()) {
            throw new ValidationException("Telefon raqamini o'zgartirish uchun akkauntda email manzil bo'lishi kerak.");
        }
        String code = otpService.sendCode(primaryEmail);

        Map<String, Object> resp = new HashMap<>();
        resp.put("sent", true);
        resp.put("channel", "email");
        if (config.isOtpExposeCode()) resp.put("devCode", code);
        return resp;
    }

    /** POST /phone/verify */
    @Transactional
    public Map<String, Object> verifyPhoneChange(UUID clientId, String code, String newPhone) {
        if (newPhone == null || !PHONE_RX.matcher(newPhone.trim()).matches()) {
            throw new ValidationException("Telefon raqami +998XXXXXXXXX formatida bo'lishi kerak.");
        }
        String normPhone = Phones.normalize(newPhone);

        Client client = clientRepo.findById(clientId)
                .orElseThrow(() -> new NotFoundException("Foydalanuvchi topilmadi."));

        // OTP key is the primary email (same channel used at request time).
        if (!otpService.verify(client.getEmail(), code)) {
            throw new ValidationException("Tasdiqlash kodi noto'g'ri yoki muddati o'tgan.");
        }

        if (normPhone.equals(client.getPhone())) {
            throw new ValidationException("Bu allaqachon sizning telefon raqamingiz.");
        }
        if (clientRepo.existsByPhone(normPhone)) {
            throw new ConflictException("Bu telefon raqam allaqachon boshqa akkauntda ishlatilmoqda.");
        }

        String oldPhone = client.getPhone();
        client.setPhone(normPhone);
        clientRepo.save(client);
        auditRepo.save(new AccountAuditLog(clientId, "PHONE_CHANGED", "old=" + oldPhone + " new=" + normPhone));

        return Map.of("phone", normPhone);
    }

    /** POST /email/change-request — OTP sent to newEmail (ownership proof before primary swap). */
    public Map<String, Object> requestEmailChange(UUID clientId, String newEmail) {
        if (newEmail == null || !EMAIL_RX.matcher(newEmail.trim()).matches()) {
            throw new ValidationException("Email formati noto'g'ri.");
        }
        String normEmail = newEmail.trim().toLowerCase();

        Client client = clientRepo.findById(clientId)
                .orElseThrow(() -> new NotFoundException("Foydalanuvchi topilmadi."));

        if (normEmail.equals(client.getEmail())) {
            throw new ValidationException("Bu allaqachon sizning email manzilingiz.");
        }
        if (clientRepo.existsByEmail(normEmail)) {
            throw new ConflictException("Bu email allaqachon boshqa akkauntda ishlatilmoqda.");
        }

        String code = otpService.sendCode(normEmail);

        Map<String, Object> resp = new HashMap<>();
        resp.put("sent", true);
        resp.put("channel", "email");
        if (config.isOtpExposeCode()) resp.put("devCode", code);
        return resp;
    }

    /** POST /email/verify */
    @Transactional
    public Map<String, Object> verifyEmailChange(UUID clientId, String code, String newEmail) {
        if (newEmail == null || !EMAIL_RX.matcher(newEmail.trim()).matches()) {
            throw new ValidationException("Email formati noto'g'ri.");
        }
        String normEmail = newEmail.trim().toLowerCase();

        Client client = clientRepo.findById(clientId)
                .orElseThrow(() -> new NotFoundException("Foydalanuvchi topilmadi."));

        // OTP key is newEmail — verifies ownership before it becomes primary.
        if (!otpService.verify(normEmail, code)) {
            throw new ValidationException("Tasdiqlash kodi noto'g'ri yoki muddati o'tgan.");
        }

        if (normEmail.equals(client.getEmail())) {
            throw new ValidationException("Bu allaqachon sizning email manzilingiz.");
        }
        if (clientRepo.existsByEmail(normEmail)) {
            throw new ConflictException("Bu email allaqachon boshqa akkauntda ishlatilmoqda.");
        }

        String oldEmail = client.getEmail();
        client.setEmail(normEmail);
        clientRepo.save(client);
        auditRepo.save(new AccountAuditLog(clientId, "EMAIL_CHANGED", "old=" + oldEmail + " new=" + normEmail));

        return Map.of("email", normEmail);
    }

    /** GET /email/secondary */
    public List<Map<String, Object>> getSecondaryEmails(UUID clientId) {
        return secondaryEmailRepo.findByClientId(clientId).stream()
                .map(se -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("id", se.getId());
                    m.put("email", se.getEmail());
                    m.put("verified", se.getVerifiedAt() != null);
                    m.put("createdAt", se.getCreatedAt());
                    return m;
                }).toList();
    }

    /** POST /email/add — upsert pending row, send OTP to that email. */
    public Map<String, Object> addSecondaryEmail(UUID clientId, String email) {
        if (email == null || !EMAIL_RX.matcher(email.trim()).matches()) {
            throw new ValidationException("Email formati noto'g'ri.");
        }
        String normEmail = email.trim().toLowerCase();

        Client client = clientRepo.findById(clientId)
                .orElseThrow(() -> new NotFoundException("Foydalanuvchi topilmadi."));

        if (normEmail.equals(client.getEmail())) {
            throw new ValidationException("Bu sizning asosiy email manzilingiz.");
        }
        if (clientRepo.existsByEmail(normEmail)) {
            throw new ConflictException("Bu email allaqachon boshqa akkauntda ishlatilmoqda.");
        }

        Optional<SecondaryEmail> existing = secondaryEmailRepo.findByClientIdAndEmail(clientId, normEmail);
        if (existing.isPresent() && existing.get().getVerifiedAt() != null) {
            throw new ConflictException("Bu email allaqachon qo'shilgan va tasdiqlangan.");
        }

        // Upsert: create pending row only if it does not already exist.
        if (existing.isEmpty()) {
            secondaryEmailRepo.save(new SecondaryEmail(clientId, normEmail));
        }

        String code = otpService.sendCode(normEmail);

        Map<String, Object> resp = new HashMap<>();
        resp.put("sent", true);
        if (config.isOtpExposeCode()) resp.put("devCode", code);
        return resp;
    }

    /** POST /email/verify-secondary */
    @Transactional
    public Map<String, Object> verifySecondaryEmail(UUID clientId, String code, String email) {
        if (email == null || !EMAIL_RX.matcher(email.trim()).matches()) {
            throw new ValidationException("Email formati noto'g'ri.");
        }
        String normEmail = email.trim().toLowerCase();

        if (!otpService.verify(normEmail, code)) {
            throw new ValidationException("Tasdiqlash kodi noto'g'ri yoki muddati o'tgan.");
        }

        SecondaryEmail se = secondaryEmailRepo.findByClientIdAndEmail(clientId, normEmail)
                .orElseThrow(() -> new NotFoundException("Ikkinchi darajali email topilmadi."));

        if (se.getVerifiedAt() != null) {
            throw new ConflictException("Bu email allaqachon tasdiqlangan.");
        }

        se.setVerifiedAt(Instant.now());
        secondaryEmailRepo.save(se);
        auditRepo.save(new AccountAuditLog(clientId, "SECONDARY_EMAIL_VERIFIED", normEmail));

        Map<String, Object> resp = new HashMap<>();
        resp.put("id", se.getId());
        resp.put("email", se.getEmail());
        resp.put("verified", true);
        return resp;
    }

    /** DELETE /email/{id} — 404 if not found or not caller's. */
    @Transactional
    public void deleteSecondaryEmail(UUID clientId, UUID secondaryEmailId) {
        SecondaryEmail se = secondaryEmailRepo.findById(secondaryEmailId)
                .orElseThrow(() -> new NotFoundException("Ikkinchi darajali email topilmadi."));

        // Return 404 (not 403) to avoid leaking existence of other clients' emails.
        if (!se.getClientId().equals(clientId)) {
            throw new NotFoundException("Ikkinchi darajali email topilmadi.");
        }

        auditRepo.save(new AccountAuditLog(clientId, "SECONDARY_EMAIL_DELETED", se.getEmail()));
        secondaryEmailRepo.delete(se);
    }
}
