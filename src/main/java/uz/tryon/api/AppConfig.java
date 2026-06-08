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

    /** Sifat tekshiruvi chegaralari (POST /api/check uchun). */
    private final Check check = new Check();

    public Check getCheck() { return check; }

    /**
     * Rasm sifati tekshiruvi sozlamalari. Hammasi env orqali sozlanadi
     * (masalan TRYON_CHECK_MIN_WIDTH), application.yml ga qarang.
     */
    public static class Check {
        /** Minimal kenglik (px). Bundan kichik bo'lsa — rad. */
        private int minWidth = 256;
        /** Minimal balandlik (px). Bundan kichik bo'lsa — rad. */
        private int minHeight = 256;
        /** Blur chegarasi (Laplacian dispersiyasi). Bundan past — xira deb ogohlantirish.
         *  Eslatma: butun rasm bo'yicha hisoblanadi, shuning uchun fon ko'p bo'lsa pasayadi —
         *  heuristik qiymat, env orqali sozlanadi. */
        private double blurMin = 10.0;
        /** Yorug'lik (o'rtacha yorqinlik 0..255) pastki chegarasi — bundan past = juda qorong'i. */
        private int brightnessMin = 30;
        /** Yorug'lik yuqori chegarasi — bundan baland = juda yorug'/yoritilgan. */
        private int brightnessMax = 235;

        /** Odam (box) ishonch chegarasi — bundan past instanslar e'tiborsiz qoldiriladi. */
        private double personScoreMin = 0.25;
        /** Tana nuqtasi (keypoint) ko'rinish chegarasi — bundan past = nuqta ko'rinmaydi. */
        private double keypointScoreMin = 0.30;

        public int getMinWidth() { return minWidth; }
        public void setMinWidth(int v) { this.minWidth = v; }

        public int getMinHeight() { return minHeight; }
        public void setMinHeight(int v) { this.minHeight = v; }

        public double getBlurMin() { return blurMin; }
        public void setBlurMin(double v) { this.blurMin = v; }

        public int getBrightnessMin() { return brightnessMin; }
        public void setBrightnessMin(int v) { this.brightnessMin = v; }

        public int getBrightnessMax() { return brightnessMax; }
        public void setBrightnessMax(int v) { this.brightnessMax = v; }

        public double getPersonScoreMin() { return personScoreMin; }
        public void setPersonScoreMin(double v) { this.personScoreMin = v; }

        public double getKeypointScoreMin() { return keypointScoreMin; }
        public void setKeypointScoreMin(double v) { this.keypointScoreMin = v; }
    }

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
