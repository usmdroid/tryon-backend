package uz.tryon.api.wallet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "credit_transactions")
public class CreditTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "client_id", nullable = false)
    private UUID clientId;

    @Column(name = "amount_msim", nullable = false)
    private long amountMsim;

    @Column(name = "type", nullable = false, length = 32)
    private String type;

    @Column(name = "balance_after_msim", nullable = false)
    private long balanceAfterMsim;

    @Column(name = "meta")
    private String meta;

    /** TRYON_DEBIT qatorlari uchun — qaysi API kalit so'rovni keltirgani (nullable: legacy/session). */
    @Column(name = "api_key_id")
    private UUID apiKeyId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected CreditTransaction() { }

    public CreditTransaction(UUID clientId, long amountMsim, String type, long balanceAfterMsim, String meta) {
        this(clientId, amountMsim, type, balanceAfterMsim, meta, null);
    }

    public CreditTransaction(UUID clientId, long amountMsim, String type, long balanceAfterMsim, String meta, UUID apiKeyId) {
        this.clientId = clientId;
        this.amountMsim = amountMsim;
        this.type = type;
        this.balanceAfterMsim = balanceAfterMsim;
        this.meta = meta;
        this.apiKeyId = apiKeyId;
    }

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getClientId() { return clientId; }
    public long getAmountMsim() { return amountMsim; }
    public String getType() { return type; }
    public long getBalanceAfterMsim() { return balanceAfterMsim; }
    public String getMeta() { return meta; }
    public UUID getApiKeyId() { return apiKeyId; }
    public Instant getCreatedAt() { return createdAt; }
}
