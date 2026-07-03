package uz.tryon.api.admin;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AppConfigRepository extends JpaRepository<AppConfigEntry, String> {
}
