package uz.tryon.api.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Resend (https://resend.com) orqali email yuborish.
 *
 * Outbound HTTP uslubi {@link uz.tryon.api.ModalClient} bilan bir xil:
 * java.net.http.HttpClient + Jackson ObjectMapper (qo'shimcha kutubxonasiz).
 *
 * Bu klient faqat provayder kaliti (RESEND_API_KEY) mavjud bo'lganda quriladi/ishlatiladi
 * — EmailOtpSender shartni tekshiradi.
 */
public class ResendEmailClient implements EmailClient {

    private static final Logger log = LoggerFactory.getLogger(ResendEmailClient.class);
    private static final String RESEND_URL = "https://api.resend.com/emails";

    private final String apiKey;
    private final String from;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    public ResendEmailClient(String apiKey, String from) {
        this.apiKey = apiKey;
        this.from = from;
    }

    @Override
    public void send(String to, String subject, String htmlBody, String textBody) {
        try {
            // Resend JSON tanasi: { from, to, subject, html, text }
            String body = mapper.writeValueAsString(Map.of(
                    "from", from,
                    "to", to,
                    "subject", subject,
                    "html", htmlBody,
                    "text", textBody
            ));

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(RESEND_URL))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    // Resend autentifikatsiyasi: Bearer <RESEND_API_KEY>
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                log.info("✉️  Resend email yuborildi: {}", to);
            } else {
                // Yuborilmasa — log'ga yozamiz, lekin oqimni buzmaymiz (kod xotirada saqlangan).
                log.warn("Resend xatosi {} — email yuborilmadi: {}", resp.statusCode(), resp.body());
            }
        } catch (Exception e) {
            log.warn("Resend bilan aloqa uzildi — email yuborilmadi: {}", e.getMessage());
        }
    }
}
