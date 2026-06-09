# Try-on Backend

Virtual try-on servisi uchun backend (Spring Boot 3, Java 17).

UI (sayt/plugin) va Modal (GPU) o'rtasidagi "aqlli darvozabon":
API kalit + domain tekshiruvi, rate limit, rasm validatsiyasi,
Modal'ga ichki secret bilan uzatish.

## Lokal ishga tushirish

```bash
mvn spring-boot:run
```

Backend `http://localhost:8080` da ishlaydi.

Sog'liq tekshiruvi:
```bash
curl http://localhost:8080/api/health
# {"status":"ok"}
```

## Sozlamalar (env o'zgaruvchilar)

Barcha sozlamalar env orqali. Lokalda `application.yml` dagi default qiymatlar ishlaydi.

| O'zgaruvchi | Ma'no | Default |
|---|---|---|
| `PORT` | Server porti | 8080 |
| `TRYON_MODAL_URL` | Modal endpoint URL | (deploy qilingan URL) |
| `TRYON_MODAL_SECRET` | Modal ichki secret | (bo'sh — keyin qo'shiladi) |
| `TRYON_API_KEYS` | Sotuvchi kalitlari (vergul bilan) | test-key-12345 |
| `TRYON_ALLOWED_ORIGINS` | Ruxsat domenlar (vergul bilan) | (bo'sh = hammasi) |
| `TRYON_RATE_LIMIT` | Daqiqasiga maks so'rov | 5 |

## API

### POST /api/tryon

Header: `X-Api-Key: <kalit>`

Body (JSON):
```json
{
  "person_image": "<base64>",
  "cloth_image": "<base64>",
  "cloth_type": "upper"
}
```

Javob: `image/webp` (muvaffaqiyat) yoki JSON xato.

### POST /api/check

Modal'ga (GPU'ga) **tegmasdan** rasm generatsiyaga yaroqliligini tekshiradi.
Frontenddagi "Tekshirish" tugmasi shuni chaqiradi.

Header: `X-Api-Key: <kalit>`

Body (JSON):
```json
{ "person_image": "<base64>", "cloth_type": "upper" }
```

Javob (JSON):
```json
{
  "ok": true,
  "clothType": "upper",
  "summary": "Rasm generatsiyaga yaroqli.",
  "checks": [
    { "id": "resolution", "label": "Rezolyutsiya", "status": "pass", "message": "..." },
    { "id": "face_count", "label": "Yuz soni",     "status": "pass", "message": "..." }
  ]
}
```

Tekshiruvlar: format/hajm, rezolyutsiya, yorug'lik, xiralik (sof Java) +
odam soni (YOLOv8), poza va tana ko'rinishi (MoveNet MultiPose). Hammasi ONNX, CPU.
`status`: `pass` / `warn` (yaroqli, lekin sifat past) / `fail` (rad) / `skip`.
`ok=false` faqat biror `fail` bo'lsa. GPU xarajati yo'q → rate limit qo'llanmaydi.

Modellar (`src/main/resources/models/`, server startda bir marta yuklanadi):
- `yolov8n.onnx` (~13 MB) — odam soni. [Kalray/yolov8](https://huggingface.co/Kalray/yolov8)
- `movenet-multipose.onnx` (~19 MB) — poza/tana. Apache-2.0,
  [Xenova/movenet-multipose-lightning](https://huggingface.co/Xenova/movenet-multipose-lightning)

Eslatma: detektorlar haqiqiy fotosuratlar uchun. Ikona/multik kabi tekis rasmlar
"odam topilmadi" deb rad etiladi (kutilgan).

## Deploy (Railway / Render)

1. Bu papkani GitHub repo'ga yuklang.
2. Railway/Render'da "New Project" → GitHub repo'ni ulang.
3. Platforma `pom.xml`ni ko'rib Java/Maven loyiha ekanini aniqlaydi.
4. Env o'zgaruvchilarni platforma sozlamalarida bering (yuqoridagi jadval).
5. Deploy.

## Keyingi bosqichlar (backend dev uchun)

- Imzolangan token (HMAC/JWT + nonce) qo'shish.
- ✅ Yuz/poza/tana detektori — `/api/check` (MoveNet) qo'shildi.
- PostgreSQL: API kalitlar va billing.
- Modal tarafida secret tekshiruvini yoqish.
- Rasm vaqtinchalik saqlash (S3/R2) + avtomatik o'chirish.
- Rate limit'ni Redis bilan (ko'p server uchun).
