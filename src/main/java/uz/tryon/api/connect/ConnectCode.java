package uz.tryon.api.connect;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Bir martalik OAuth-uslubida ulash kodi (authorize → exchange oqimi). */
@Entity
@Table(name = "connect_codes")
public class ConnectCode {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Kodning SHA-256 hash'i (plaintext saqlanmaydi). */
    @Column(name = "code_hash", nullable = false, unique = true)
    private String codeHash;

    @Column(name = "client_id", nullable = false)
    private UUID clientId;

    @Column(name = "api_key_id", nullable = false)
    private UUID apiKeyId;

    @Column(name = "redirect_uri", nullable = false, columnDefinition = "TEXT")
    private String redirectUri;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** Ishlatilgan vaqt; NULL = hali ishlatilmagan. */
    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ConnectCode() { }

    public ConnectCode(String codeHash, UUID clientId, UUID apiKeyId, String redirectUri, Instant expiresAt) {
        this.codeHash = codeHash;
        this.clientId = clientId;
        this.apiKeyId = apiKeyId;
        this.redirectUri = redirectUri;
        this.expiresAt = expiresAt;
    }

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getCodeHash() { return codeHash; }
    public UUID getClientId() { return clientId; }
    public UUID getApiKeyId() { return apiKeyId; }
    public String getRedirectUri() { return redirectUri; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getConsumedAt() { return consumedAt; }
    public Instant getCreatedAt() { return createdAt; }
}
