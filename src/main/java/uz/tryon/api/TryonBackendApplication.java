package uz.tryon.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Try-on backend — kirish nuqtasi.
 *
 * Vazifa: UI (sayt/plugin) va Modal (GPU) o'rtasidagi "aqlli darvozabon".
 * - API kalit + domain (Origin) tekshiruvi
 * - rate limit (GPU xarajati himoyasi)
 * - rasm validatsiyasi
 * - Modal'ga ichki secret bilan uzatish
 *
 * Sozlamalar env o'zgaruvchilar orqali (application.yml ga qarang).
 */
@SpringBootApplication
public class TryonBackendApplication {
    public static void main(String[] args) {
        SpringApplication.run(TryonBackendApplication.class, args);
    }
}
