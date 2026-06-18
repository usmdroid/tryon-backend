package uz.tryon.api.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {
    List<ApiKey> findByClientIdOrderByCreatedAtDesc(UUID clientId);
    Optional<ApiKey> findByIdAndClientId(UUID id, UUID clientId);
}
