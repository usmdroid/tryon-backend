package uz.tryon.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import uz.tryon.api.util.AuthHashUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * Modal (GPU) bilan aloqa.
 *
 * Backend Modal'ni HMAC-SHA256 imzolangan so'rov bilan chaqiradi.
 * Imzo: HMAC_SHA256(secret, timestamp + "." + body), hex string.
 * Modal URL va secret faqat shu yerda (env'dan) — hech qachon frontendga chiqmaydi.
 */
@Service
public class ModalClient {

    private static final Logger log = LoggerFactory.getLogger(ModalClient.class);

    private final AppConfig config;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    public ModalClient(AppConfig config) {
        this(config, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build());
    }

    /** Package-private secondary constructor for unit testing with a mock HttpClient. */
    ModalClient(AppConfig config, HttpClient http) {
        this.config = config;
        this.http = http;
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

            long timestamp = System.currentTimeMillis() / 1000;
            String signature = hmacSha256Hex(config.getModalSecret(), timestamp + "." + body);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(config.getModalUrl()))
                    .timeout(Duration.ofSeconds(120))
                    .header("Content-Type", "application/json")
                    .header("X-Sima-Timestamp", Long.toString(timestamp))
                    .header("X-Sima-Signature", signature)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());

            if (resp.statusCode() == 200) {
                return new Result(true, resp.body(), null);
            }

            if (resp.statusCode() == 403) {
                String bodyStr = new String(resp.body(), StandardCharsets.UTF_8);
                log.error("MODAL_AUTH_FAIL: status=403, body={}. Possible clock drift or invalid signature.", bodyStr);
                return new Result(false, null, "Modal auth xatosi");
            }

            return new Result(false, null, "Modal xatosi: " + resp.statusCode());

        } catch (Exception e) {
            return new Result(false, null, "Modal bilan aloqa uzildi: " + e.getMessage());
        }
    }

    /**
     * HMAC-SHA256 of {@code message} keyed with {@code secret}, returned as lowercase hex.
     * Package-private so the regression test can verify the algorithm without reflection.
     * Both sides (Java sign, Python verify) must use identical byte sequences:
     *   msg = timestamp_string.getBytes(UTF-8) + ".".getBytes(UTF-8) + body.getBytes(UTF-8)
     */
    static String hmacSha256Hex(String secret, String message) {
        return AuthHashUtils.hmacSha256Hex(secret, message);
    }
}
