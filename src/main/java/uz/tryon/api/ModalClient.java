package uz.tryon.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Modal (GPU) bilan aloqa.
 *
 * Backend Modal'ni ichki secret bilan chaqiradi. Modal endpoint URL'i
 * va secret faqat shu yerda (env'dan) — hech qachon frontendga chiqmaydi.
 *
 * Modal JSON qabul qiladi: { person_image, cloth_image, cloth_type }
 * va WebP rasm (bytes) qaytaradi.
 */
@Service
public class ModalClient {

    private final AppConfig config;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    public ModalClient(AppConfig config) {
        this.config = config;
    }

    /** Natija: muvaffaqiyat bo'lsa rasm baytlari, aks holda xato. */
    public record Result(boolean ok, byte[] image, String error) {}

    public Result generate(String personB64, String clothB64, String clothType) {
        try {
            String body = mapper.writeValueAsString(Map.of(
                    "person_image", personB64,
                    "cloth_image", clothB64,
                    "cloth_type", clothType
            ));

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(config.getModalUrl()))
                    .timeout(Duration.ofSeconds(120))   // generatsiya ~17s, cold start ehtimoli uchun zaxira
                    .header("Content-Type", "application/json")
                    // Ichki secret — Modal tarafi shuni tekshiradi (keyin qo'shiladi)
                    .header("X-Internal-Secret", config.getModalSecret() == null ? "" : config.getModalSecret())
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());

            if (resp.statusCode() == 200) {
                return new Result(true, resp.body(), null);
            }
            return new Result(false, null, "Modal xatosi: " + resp.statusCode());

        } catch (Exception e) {
            return new Result(false, null, "Modal bilan aloqa uzildi: " + e.getMessage());
        }
    }
}
