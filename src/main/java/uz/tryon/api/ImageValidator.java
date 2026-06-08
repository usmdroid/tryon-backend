package uz.tryon.api;

import org.springframework.stereotype.Service;

import java.util.Base64;

/**
 * Rasm validatsiyasi — Modal'ga (GPU'ga) yuborishdan OLDIN.
 *
 * Hozir: format (magic bytes) + hajm tekshiruvi.
 * KEYIN (backend dev): yengil yuz/odam detektori (MediaPipe vb.) —
 *   odam bor-yo'qligi, yuzlar soni. Bu yerga qo'shiladi.
 */
@Service
public class ImageValidator {

    private final AppConfig config;

    public ImageValidator(AppConfig config) {
        this.config = config;
    }

    /** Tekshiruv natijasi. ok=false bo'lsa, reason foydalanuvchiga ko'rsatiladi. */
    public record Result(boolean ok, String reason) {
        static Result good() { return new Result(true, null); }
        static Result bad(String r) { return new Result(false, r); }
    }

    public Result validate(String base64Image) {
        if (base64Image == null || base64Image.isBlank()) {
            return Result.bad("Rasm yuborilmadi.");
        }

        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64Image);
        } catch (IllegalArgumentException e) {
            return Result.bad("Rasm formati noto'g'ri (base64 emas).");
        }

        if (bytes.length > config.getMaxImageBytes()) {
            return Result.bad("Rasm hajmi juda katta.");
        }
        if (bytes.length < 100) {
            return Result.bad("Rasm juda kichik yoki buzilgan.");
        }

        if (!isImage(bytes)) {
            return Result.bad("Fayl rasm emas (JPEG/PNG/WebP kerak).");
        }

        return Result.good();
    }

    /** Magic bytes orqali rasm formatini tekshirish (JPEG, PNG, WebP). */
    private boolean isImage(byte[] b) {
        if (b.length < 12) return false;
        // JPEG: FF D8 FF
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return true;
        // PNG: 89 50 4E 47
        if ((b[0] & 0xFF) == 0x89 && b[1] == 0x50 && b[2] == 0x4E && b[3] == 0x47) return true;
        // WebP: "RIFF"...."WEBP"
        if (b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return true;
        return false;
    }
}
