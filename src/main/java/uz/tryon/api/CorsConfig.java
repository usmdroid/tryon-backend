package uz.tryon.api;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS — brauzer saytdan backend'ga so'rov yubora olishi uchun.
 *
 * Hozir ruxsat etilgan domenlar AppConfig'dan olinadi (allowedOrigins).
 * Agar ro'yxat bo'sh bo'lsa, demo qulayligi uchun hammasiga ruxsat (*).
 * Production'da albatta aniq domenlar ko'rsatiladi.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    private final AppConfig config;

    public CorsConfig(AppConfig config) {
        this.config = config;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        var mapping = registry.addMapping("/api/**")
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("*");

        if (config.getAllowedOrigins() != null && !config.getAllowedOrigins().isEmpty()) {
            mapping.allowedOrigins(config.getAllowedOrigins().toArray(new String[0]));
        } else {
            mapping.allowedOriginPatterns("*");
        }
    }
}
