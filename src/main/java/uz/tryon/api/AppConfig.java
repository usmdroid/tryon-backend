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

    /** Sessiya tokenini imzolash maxfiy kaliti (HMAC). Production'da albatta env orqali o'zgartiriladi. */
    private String tokenSecret = "dev-secret-change-me";

    /** API kalitlarni teskari shifrlash siri (AES). Production'da TRYON_KEY_ENC_SECRET bilan o'rnating. */
    private String keyEncSecret = "dev-secret-change-me";

    /** Sessiya tokeni amal qilish muddati (soniya). Default 5 daqiqa. */
    private long tokenTtlSeconds = 300;

    /** OTP kod amal qilish muddati (soniya). Default 5 daqiqa. */
    private long otpTtlSeconds = 300;

    /** Dev/test uchun qat'iy OTP kod (bo'sh = tasodifiy). Production'da bo'sh qoldiriladi. */
    private String otpFixedCode = "";

    /** Dev: true bo'lsa send-otp javobida kod ham qaytadi (frontend toast uchun). Production'da false! */
    private boolean otpExposeCode = false;

    /** Email provayder nomi (masalan "resend"). Bo'sh = log rejimi (real email yuborilmaydi). */
    private String mailProvider = "";

    /** Email "from" manzili (masalan noreply@trysima.uz). Bo'sh = log rejimi. */
    private String mailFrom = "";

    /** Resend API kaliti. Bo'sh = log rejimi. Domen tayyor bo'lganda to'ldiriladi. */
    private String resendApiKey = "";

    /** Redis URL (rate limit uchun). Bo'sh = xotirada (in-memory) ishlaydi. */
    private String redisUrl = "";

    /** R2 endpoint (natija saqlash uchun). Bo'sh = saqlash o'chiq. */
    private String r2Endpoint = "";

    /** R2 bucket nomi. */
    private String r2Bucket = "";

    /** R2 access key. */
    private String r2AccessKey = "";

    /** R2 secret key. */
    private String r2SecretKey = "";

    /** ML detektorlarni yoqish (xotira tejash uchun). false = /check o'sha tekshiruvni skip qiladi, model yuklanmaydi. */
    private boolean yoloEnabled = true;
    private boolean poseEnabled = true;

    /** Texnik tanaffus — true bo'lsa /session, /check, /tryon endpoint'lari 503 qaytaradi (xotira tejash). */
    private boolean maintenance = false;

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

        /** Yuz/quloq nisbati minimal chegarasi — orqa aniqlash uchun. */
        private double faceEarRatioMin = 0.5;
        /** Ko'z kuchi chegarasi — kuchli ko'z hisoblash uchun. */
        private double eyeStrongThreshold = 0.4;
        /** Burun kuchi chegarasi — orqa aniqlash uchun. */
        private double noseStrongThreshold = 0.3;
        /** Yelka simmetriyasi maksimal farqi — yon tomondan aniqlash uchun. */
        private double shoulderAsymmetryMax = 0.3;

        public double getFaceEarRatioMin() { return faceEarRatioMin; }
        public void setFaceEarRatioMin(double v) { this.faceEarRatioMin = v; }

        public double getEyeStrongThreshold() { return eyeStrongThreshold; }
        public void setEyeStrongThreshold(double v) { this.eyeStrongThreshold = v; }

        public double getNoseStrongThreshold() { return noseStrongThreshold; }
        public void setNoseStrongThreshold(double v) { this.noseStrongThreshold = v; }

        public double getShoulderAsymmetryMax() { return shoulderAsymmetryMax; }
        public void setShoulderAsymmetryMax(double v) { this.shoulderAsymmetryMax = v; }

        /** Debug rejimi: true bo'lsa /api/check javobi ichida poza signallari qo'shimcha CheckItem sifatida keladi. */
        private boolean debug = false;
        public boolean isDebug() { return debug; }
        public void setDebug(boolean v) { this.debug = v; }
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

    public String getTokenSecret() { return tokenSecret; }
    public void setTokenSecret(String v) { this.tokenSecret = v; }

    public String getKeyEncSecret() { return keyEncSecret; }
    public void setKeyEncSecret(String v) { this.keyEncSecret = v; }

    public long getTokenTtlSeconds() { return tokenTtlSeconds; }
    public void setTokenTtlSeconds(long v) { this.tokenTtlSeconds = v; }

    public long getOtpTtlSeconds() { return otpTtlSeconds; }
    public void setOtpTtlSeconds(long v) { this.otpTtlSeconds = v; }

    public String getOtpFixedCode() { return otpFixedCode; }
    public void setOtpFixedCode(String v) { this.otpFixedCode = v; }

    public boolean isOtpExposeCode() { return otpExposeCode; }
    public void setOtpExposeCode(boolean v) { this.otpExposeCode = v; }

    public boolean isYoloEnabled() { return yoloEnabled; }
    public void setYoloEnabled(boolean v) { this.yoloEnabled = v; }

    public boolean isPoseEnabled() { return poseEnabled; }
    public void setPoseEnabled(boolean v) { this.poseEnabled = v; }

    public boolean isMaintenance() { return maintenance; }
    public void setMaintenance(boolean v) { this.maintenance = v; }

    public String getMailProvider() { return mailProvider; }
    public void setMailProvider(String v) { this.mailProvider = v; }

    public String getMailFrom() { return mailFrom; }
    public void setMailFrom(String v) { this.mailFrom = v; }

    public String getResendApiKey() { return resendApiKey; }
    public void setResendApiKey(String v) { this.resendApiKey = v; }

    public String getRedisUrl() { return redisUrl; }
    public void setRedisUrl(String v) { this.redisUrl = v; }

    public String getR2Endpoint() { return r2Endpoint; }
    public void setR2Endpoint(String v) { this.r2Endpoint = v; }

    public String getR2Bucket() { return r2Bucket; }
    public void setR2Bucket(String v) { this.r2Bucket = v; }

    public String getR2AccessKey() { return r2AccessKey; }
    public void setR2AccessKey(String v) { this.r2AccessKey = v; }

    public String getR2SecretKey() { return r2SecretKey; }
    public void setR2SecretKey(String v) { this.r2SecretKey = v; }
}
