package uz.tryon.api;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Sozlamalar — barchasi env o'zgaruvchilardan keladi (application.yml orqali).
 * Maxfiy qiymatlar (Modal secret) hech qachon kodda yozilmaydi.
 */
@Component
@ConfigurationProperties(prefix = "tryon")
public class AppConfig {

    /** Modal web endpoint URL (rasm yuboriladigan joy). */
    private String modalUrl;

    /** Modal'ga yuboriladigan ichki secret (Modal tarafi tekshiradi). */
    private String modalSecret;

    /** Ruxsat etilgan API kalitlar (sotuvchilar). Demo uchun ro'yxat; keyin DB'dan. */
    private List<String> apiKeys;

    /** Ruxsat etilgan domenlar (Origin allowlist). */
    private List<String> allowedOrigins;

    /** Bir API kalit uchun daqiqasiga maksimal so'rov. */
    private int rateLimitPerMinute = 5;

    /** Maksimal rasm hajmi (bayt). Default 8 MB. */
    private long maxImageBytes = 8L * 1024 * 1024;

    public String getModalUrl() { return modalUrl; }
    public void setModalUrl(String v) { this.modalUrl = v; }

    public String getModalSecret() { return modalSecret; }
    public void setModalSecret(String v) { this.modalSecret = v; }

    public List<String> getApiKeys() { return apiKeys; }
    public void setApiKeys(List<String> v) { this.apiKeys = v; }

    public List<String> getAllowedOrigins() { return allowedOrigins; }
    public void setAllowedOrigins(List<String> v) { this.allowedOrigins = v; }

    public int getRateLimitPerMinute() { return rateLimitPerMinute; }
    public void setRateLimitPerMinute(int v) { this.rateLimitPerMinute = v; }

    public long getMaxImageBytes() { return maxImageBytes; }
    public void setMaxImageBytes(long v) { this.maxImageBytes = v; }
}
