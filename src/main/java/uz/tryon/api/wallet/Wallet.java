package uz.tryon.api.wallet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "wallets")
public class Wallet {

    @Id
    @Column(name = "client_id")
    private UUID clientId;

    @Column(name = "balance_msim", nullable = false)
    private long balanceMsim;

    @Column(name = "total_requests", nullable = false)
    private long totalRequests;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Wallet() { }

    public Wallet(UUID clientId) {
        this.clientId = clientId;
        this.balanceMsim = 0;
        this.totalRequests = 0;
    }

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getClientId() { return clientId; }
    public long getBalanceMsim() { return balanceMsim; }
    public long getTotalRequests() { return totalRequests; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setBalanceMsim(long v) { this.balanceMsim = v; }
    public void setTotalRequests(long v) { this.totalRequests = v; }
}
