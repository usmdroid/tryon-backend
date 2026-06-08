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

## Deploy (Railway / Render)

1. Bu papkani GitHub repo'ga yuklang.
2. Railway/Render'da "New Project" → GitHub repo'ni ulang.
3. Platforma `pom.xml`ni ko'rib Java/Maven loyiha ekanini aniqlaydi.
4. Env o'zgaruvchilarni platforma sozlamalarida bering (yuqoridagi jadval).
5. Deploy.

## Keyingi bosqichlar (backend dev uchun)

- Imzolangan token (HMAC/JWT + nonce) qo'shish.
- Yengil yuz/odam detektori (ImageValidator ichida TODO).
- PostgreSQL: API kalitlar va billing.
- Modal tarafida secret tekshiruvini yoqish.
- Rasm vaqtinchalik saqlash (S3/R2) + avtomatik o'chirish.
- Rate limit'ni Redis bilan (ko'p server uchun).
