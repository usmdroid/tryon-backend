package uz.tryon.api.account;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "account_audit_log")
public class AccountAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "client_id", nullable = false)
    private UUID clientId;

    @Column(nullable = false)
    private String action;

    @Column
    private String detail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AccountAuditLog() { }

    public AccountAuditLog(UUID clientId, String action, String detail) {
        this.clientId = clientId;
        this.action = action;
        this.detail = detail;
    }

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getClientId() { return clientId; }
    public String getAction() { return action; }
    public String getDetail() { return detail; }
    public Instant getCreatedAt() { return createdAt; }
}
