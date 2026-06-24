package uz.tryon.api.account;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface AccountAuditLogRepository extends JpaRepository<AccountAuditLog, UUID> {
}
