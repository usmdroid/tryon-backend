package uz.tryon.api.devsandbox;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "dev_sandbox_keys")
public class DevSandboxKey {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "dev_key", nullable = false, unique = true, length = 64)
    private String devKey;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "used_count", nullable = false)
    private int usedCount;

    @Column(name = "max_count", nullable = false)
    private int maxCount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected DevSandboxKey() {}

    public DevSandboxKey(String devKey, UUID createdBy) {
        this.devKey = devKey;
        this.createdBy = createdBy;
        this.usedCount = 0;
        this.maxCount = 20;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getDevKey() { return devKey; }
    public UUID getCreatedBy() { return createdBy; }
    public int getUsedCount() { return usedCount; }
    public int getMaxCount() { return maxCount; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastUsedAt() { return lastUsedAt; }
    public Instant getRevokedAt() { return revokedAt; }

    public void setRevokedAt(Instant revokedAt) { this.revokedAt = revokedAt; }
    public void setLastUsedAt(Instant lastUsedAt) { this.lastUsedAt = lastUsedAt; }
}
