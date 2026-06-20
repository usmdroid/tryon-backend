package uz.tryon.api.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {
    List<ApiKey> findByClientIdOrderByCreatedAtDesc(UUID clientId);
    Optional<ApiKey> findByIdAndClientId(UUID id, UUID clientId);
    Optional<ApiKey> findByKeyHash(String keyHash);
    long countByClientIdAndRevokedAtIsNull(UUID clientId);

    /** Kalit oxirgi ishlatilgan vaqtini yangilaydi (load-mutate-save'siz, bitta UPDATE). */
    @Modifying
    @Query("UPDATE ApiKey k SET k.lastUsedAt = :ts WHERE k.id = :id")
    void touchLastUsedAt(@Param("id") UUID id, @Param("ts") Instant ts);
}
