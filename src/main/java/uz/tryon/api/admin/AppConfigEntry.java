package uz.tryon.api.admin;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "app_config")
public class AppConfigEntry {

    @Id
    @Column(name = "config_key", length = 100)
    private String configKey;

    @Column(name = "config_value", nullable = false, length = 255)
    private String configValue;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "updated_by")
    private UUID updatedBy;

    protected AppConfigEntry() {}

    public AppConfigEntry(String configKey, String configValue, UUID updatedBy) {
        this.configKey = configKey;
        this.configValue = configValue;
        this.updatedAt = Instant.now();
        this.updatedBy = updatedBy;
    }

    public String getConfigKey() { return configKey; }
    public String getConfigValue() { return configValue; }
    public Instant getUpdatedAt() { return updatedAt; }
    public UUID getUpdatedBy() { return updatedBy; }

    public void setValue(String value, UUID byClientId) {
        this.configValue = value;
        this.updatedAt = Instant.now();
        this.updatedBy = byClientId;
    }
}
