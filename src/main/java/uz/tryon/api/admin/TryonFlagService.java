package uz.tryon.api.admin;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.tryon.api.account.AccountAuditLog;
import uz.tryon.api.account.AccountAuditLogRepository;
import uz.tryon.api.auth.Client;

@Service
public class TryonFlagService {

    static final String KEY = "TRYON_ENABLED";
    private static final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    private final AppConfigRepository configs;
    private final AccountAuditLogRepository auditLogs;

    public TryonFlagService(AppConfigRepository configs, AccountAuditLogRepository auditLogs) {
        this.configs = configs;
        this.auditLogs = auditLogs;
    }

    /** Returns current TRYON_ENABLED flag. Defaults to true if row is missing. */
    public boolean isEnabled() {
        return configs.findById(KEY)
                .map(e -> "true".equalsIgnoreCase(e.getConfigValue()))
                .orElse(true);
    }

    /**
     * Verifies super-admin's own password, then persists the new flag value and writes an audit row.
     * @throws WrongPasswordException if password does not match — flag is NOT changed.
     */
    @Transactional
    public boolean toggle(Client admin, boolean enable, String password) {
        if (!encoder.matches(password, admin.getPasswordHash())) {
            throw new WrongPasswordException();
        }
        AppConfigEntry entry = configs.findById(KEY)
                .orElseGet(() -> new AppConfigEntry(KEY, "true", admin.getId()));
        entry.setValue(enable ? "true" : "false", admin.getId());
        configs.save(entry);
        auditLogs.save(new AccountAuditLog(
                admin.getId(),
                enable ? "TRYON_FLAG_ON" : "TRYON_FLAG_OFF",
                null));
        return enable;
    }

    public static class WrongPasswordException extends RuntimeException {
        public WrongPasswordException() { super("Parol noto'g'ri."); }
    }
}
