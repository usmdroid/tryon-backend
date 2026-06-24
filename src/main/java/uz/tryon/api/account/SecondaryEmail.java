package uz.tryon.api.account;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "secondary_emails")
public class SecondaryEmail {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "client_id", nullable = false)
    private UUID clientId;

    @Column(nullable = false)
    private String email;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected SecondaryEmail() { }

    public SecondaryEmail(UUID clientId, String email) {
        this.clientId = clientId;
        this.email = email;
    }

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getClientId() { return clientId; }
    public String getEmail() { return email; }
    public Instant getVerifiedAt() { return verifiedAt; }
    public Instant getCreatedAt() { return createdAt; }

    public void setVerifiedAt(Instant verifiedAt) { this.verifiedAt = verifiedAt; }
}
