package uz.tryon.api.telemetry;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tryon_events")
public class TryOnEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "ts", nullable = false)
    private Instant ts;

    @Column(name = "device_id", length = 64)
    private String deviceId;

    @Column(name = "platform", nullable = false, length = 16)
    private String platform;

    @Column(name = "origin", nullable = false, length = 16)
    private String origin;

    @Column(name = "partner_id")
    private UUID partnerId;

    @Column(name = "product_id", length = 128)
    private String productId;

    @Column(name = "cloth_type", length = 32)
    private String clothType;

    @Column(name = "result", nullable = false, length = 16)
    private String result;

    @Column(name = "fail_reason", length = 255)
    private String failReason;

    @Column(name = "duration_ms")
    private Long durationMs;

    protected TryOnEvent() {}

    public TryOnEvent(String platform, String origin, UUID partnerId, String deviceId,
                      String productId, String clothType, String result, String failReason,
                      Long durationMs) {
        this.ts = Instant.now();
        this.platform = platform;
        this.origin = origin;
        this.partnerId = partnerId;
        this.deviceId = deviceId;
        this.productId = productId;
        this.clothType = clothType;
        this.result = result;
        this.failReason = failReason;
        this.durationMs = durationMs;
    }

    public UUID getId() { return id; }
    public Instant getTs() { return ts; }
    public String getDeviceId() { return deviceId; }
    public String getPlatform() { return platform; }
    public String getOrigin() { return origin; }
    public UUID getPartnerId() { return partnerId; }
    public String getProductId() { return productId; }
    public String getClothType() { return clothType; }
    public String getResult() { return result; }
    public String getFailReason() { return failReason; }
    public Long getDurationMs() { return durationMs; }
}
