package uz.tryon.api.devsandbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DevSandboxKeyRepository extends JpaRepository<DevSandboxKey, UUID> {

    Optional<DevSandboxKey> findByDevKey(String devKey);

    List<DevSandboxKey> findByCreatedByOrderByCreatedAtDesc(UUID createdBy);

    List<DevSandboxKey> findAllByOrderByCreatedAtDesc();

    /**
     * Atomically increments used_count and sets last_used_at only when
     * used_count < max_count AND key is not revoked.
     * Returns 1 if updated (slot was available), 0 if guard failed.
     */
    @Modifying
    @Query("UPDATE DevSandboxKey k SET k.usedCount = k.usedCount + 1, k.lastUsedAt = :now " +
           "WHERE k.id = :id AND k.usedCount < k.maxCount AND k.revokedAt IS NULL")
    int tryIncrementUsed(@Param("id") UUID id, @Param("now") Instant now);
}
