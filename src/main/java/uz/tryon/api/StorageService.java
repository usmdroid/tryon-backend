package uz.tryon.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.net.URI;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Natija rasmini R2 (Cloudflare) yoki S3 mos omboriga yuklash.
 *
 * Faqat natija rasmi saqlanadi — kirish rasmlari (shaxs/kiyim) SAQLANMAYDI (maxfiylik).
 * R2 sozlangan bo'lmasa saqlash o'chiq — lokal/test uchun qulay.
 * Avto-o'chirish: R2 bucket lifecycle qoidasi orqali (~7 kun), kodda emas.
 */
@Service
public class StorageService {

    private static final Logger log = LoggerFactory.getLogger(StorageService.class);

    private final AppConfig config;
    private final S3Client s3; // null = R2 sozlanmagan

    public StorageService(AppConfig config) {
        this.config = config;
        this.s3 = buildClient(config);
        if (s3 != null) {
            log.info("StorageService: R2/S3 yoqildi (bucket: {})", config.getR2Bucket());
        } else {
            log.info("StorageService: saqlash o'chiq (R2 sozlanmagan)");
        }
    }

    private static S3Client buildClient(AppConfig cfg) {
        String endpoint = cfg.getR2Endpoint();
        String accessKey = cfg.getR2AccessKey();
        String secretKey = cfg.getR2SecretKey();
        if (isBlank(endpoint) || isBlank(accessKey) || isBlank(secretKey)) {
            return null;
        }
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of("auto"))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .build();
    }

    public boolean isEnabled() {
        return s3 != null && !isBlank(config.getR2Bucket());
    }

    /**
     * Natija rasmini omborga yuklaydi.
     * @return object key, masalan: results/&lt;clientId&gt;/&lt;uuid&gt;.webp
     */
    public String putResult(byte[] webp, UUID clientId) {
        if (!isEnabled()) return null;
        String key = "results/" + clientId + "/" + UUID.randomUUID() + ".webp";
        s3.putObject(
                PutObjectRequest.builder()
                        .bucket(config.getR2Bucket())
                        .key(key)
                        .contentType("image/webp")
                        .build(),
                RequestBody.fromBytes(webp)
        );
        return key;
    }

    /**
     * Fon ipida yuklash — asosiy so'rov javobini kechiktirmaydi.
     * Xato bo'lsa faqat log — so'rov hech qachon muvaffaqiyatsiz bo'lmaydi.
     */
    public void uploadAsync(byte[] webp, UUID clientId) {
        if (!isEnabled()) return;
        UUID effectiveId = (clientId != null) ? clientId : UUID.randomUUID();
        CompletableFuture.runAsync(() -> {
            try {
                String key = putResult(webp, effectiveId);
                log.debug("R2 yuklandi: {}", key);
            } catch (Exception e) {
                log.error("R2 yuklash muvaffaqiyatsiz (so'rov ta'sirlanmaydi): {}", e.getMessage());
            }
        });
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
