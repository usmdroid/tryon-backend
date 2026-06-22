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
| `REDIS_URL` | Redis URL (rate limit uchun) | (bo'sh = xotirada ishlaydi) |
| `MAIL_PROVIDER` | Email provayder ("resend") | (bo'sh = log rejimi) |
| `MAIL_FROM` | "From" manzili (masalan noreply@trysima.uz) | (bo'sh = log rejimi) |
| `RESEND_API_KEY` | Resend API kaliti | (bo'sh = log rejimi) |
| `R2_ENDPOINT` | Cloudflare R2 endpoint URL | (bo'sh = saqlash o'chiq) |
| `R2_BUCKET` | R2 bucket nomi | (bo'sh = saqlash o'chiq) |
| `R2_ACCESS_KEY` | R2 access key | (bo'sh = saqlash o'chiq) |
| `R2_SECRET_KEY` | R2 secret key | (bo'sh = saqlash o'chiq) |
| `TRYON_RESULT_RETENTION_DAYS` | Natija saqlash muddati (kun) | 7 (lifecycle qoidasi orqali) |

### Redis ulanish formatlari

```
REDIS_URL=redis://localhost:6379
REDIS_URL=redis://:password@host:6379
REDIS_URL=redis://user:password@host:6379/0
```

Sozlanmasa server xotirada ishlaydi (lokal/test uchun qulay).

### R2 natijalarini saqlash sozlamasi

Cloudflare R2 darmonida yoqish:

1. R2 bucket yarating (masalan `sima-results`).
2. API token yarating: Object Read & Write + Bucket-level ruxsat.
3. Quyidagi env o'zgaruvchilarni bering:
   ```
   R2_ENDPOINT=https://<account-id>.r2.cloudflarestorage.com
   R2_BUCKET=sima-results
   R2_ACCESS_KEY=<access-key-id>
   R2_SECRET_KEY=<secret-access-key>
   ```

**Avto-o'chirish (7 kun lifecycle):**
Cloudflare R2 → bucket → Settings → Lifecycle rules:
- Rule name: `delete-results`
- Prefix: `results/`
- Days until expiry: `7` (yoki `TRYON_RESULT_RETENTION_DAYS` da belgilangan qiymat)

Faqat natija rasmlari saqlanadi (`results/<clientId>/<uuid>.webp`).
Kirish rasmlari (shaxs/kiyim) hech qachon saqlanmaydi — maxfiylik.
Saqlash muddati `TRYON_RESULT_RETENTION_DAYS` bilan hujjatlanadi; haqiqiy muddatni
R2 lifecycle qoidasida o'rnatish kerak (kod buni boshqarmaydi).

### Email orqali OTP (tasdiqlash kodi)

Registratsiyadan oldin `POST /api/auth/send-otp` 6 xonali kodni foydalanuvchi
**email** manziliga yuboradi. Kod email bo'yicha xotirada saqlanadi (TTL
`TRYON_OTP_TTL_SECONDS`), keyin `register` o'sha email bo'yicha tekshiriladi.

**Log rejimi (default):** `MAIL_PROVIDER`, `MAIL_FROM`, `RESEND_API_KEY` bo'sh bo'lsa
real email **yuborilmaydi** — kod faqat server log'iga yoziladi (WARN: "email provider
ulanmagan — kod log'ga yozildi"). Bu dev/test'ni env'siz ishlatish uchun.

**Domen tayyor bo'lganda yoqish:**

1. Domen oling va Resend'da uni tasdiqlang (DNS yozuvlari: SPF/DKIM).
2. Resend'da API kalit yarating.
3. Quyidagi env o'zgaruvchilarni bering:
   ```
   MAIL_PROVIDER=resend
   MAIL_FROM=noreply@<sizning-domeningiz>   # masalan noreply@trysima.uz
   RESEND_API_KEY=<resend-api-kalit>
   ```
4. Serverni qayta ishga tushiring. Kodga tegmasdan real email yuborish boshlanadi.

Uchta qiymatdan birortasi bo'sh bo'lsa — tizim avtomatik log rejimida qoladi
(yarim-sozlangan holatda real email yuborilmaydi).

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

### POST /api/session

Sessiya tokenini zarb qiladi — buni **do'kon serveri** (server-server, `sk_` bilan) chaqiradi.
Token brauzerga beriladi va `/api/tryon`da `Authorization: Bearer <token>` sifatida ishlatiladi.

Header: `X-Api-Key: <sk_>` → Javob: `{ "token": "...", "expiresIn": 300 }`

Token: HMAC-SHA256 imzolangan (shifrlanmagan — ichida sir yo'q), qisqa muddatli (TTL),
bir martali (nonce). `sk_` token ichiga tushmaydi (clientId = kalit xeshi).
`/api/tryon` tokenni **bir marta** ishlatadi (consume); `/api/check` ishlatmaydi (arzon amal).
`/api/tryon` va `/api/check` eski `X-Api-Key`ni ham qabul qiladi (moslik).

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

## Connect oqimi (CMS plaginlari uchun)

WordPress yoki Shopify plaginlari Sima'ga kalit **qo'lda nusxalamasdan** avtomatik olishi mumkin.
Mavjud manual kalit yaratish/nusxalash oqimi to'liq saqlanadi.

### Oqim

```
1. Plugin foydalanuvchini Sima'ga yo'naltiradi:
   GET https://sima.uz/connect?redirect_uri=https://shop.uz/sima/callback&state=RANDOM

2. Sima sahifasida foydalanuvchi login qiladi (bo'lmasa) va kalit tanlaydi.
   Domen: "shop.uz saytiga ulanmoqchimisiz?" — tasdiqlaydi.

3. Frontend backendga:
   POST /api/connect/authorize
   Authorization: Bearer <session-token>
   { "apiKeyId": "...", "redirectUri": "https://shop.uz/sima/callback", "state": "RANDOM" }
   → { "code": "one-time-code" }

4. Frontend brauzerni yo'naltiradi:
   https://shop.uz/sima/callback?code=one-time-code&state=RANDOM

5. Plugin SERVERI (browser emas!) koddan kalit oladi:
   POST https://sima-backend.railway.app/api/connect/exchange
   Content-Type: application/json
   { "code": "one-time-code" }
   → { "key": "sk_..." }
```

### Xavfsizlik

- `code` bir martalik, TTL ~5 daqiqa, SHA-256 hash ko'rinishida saqlanadi.
- Kalit **brauzerga hech qachon** chiqmaydi — faqat server-server exchange orqali beriladi.
- `redirect_uri` faqat `https` yoki `localhost http` qabul qilinadi.
- Eski kalitlar (`keyEnc=null`) va bekor qilingan kalitlar tanlab bo'lmaydi.

### Plugin serveridan misol (curl)

```bash
# 4-qadamdan callback'da ?code=... parametri keladi
CODE="one-time-code-from-callback"

curl -s -X POST https://sima-backend.railway.app/api/connect/exchange \
  -H "Content-Type: application/json" \
  -d "{\"code\": \"$CODE\"}"
# Javob: {"key":"sk_..."}
```

Kalit olingandan so'ng plugin uni xavfsiz joyda (masalan, `.env` faylida) saqlaydi
va `POST /api/tryon` da `X-Api-Key: sk_...` sifatida ishlatadi.

## Keyingi bosqichlar (backend dev uchun)

- ✅ Imzolangan token (HMAC + nonce) qo'shildi.
- ✅ Yuz/poza/tana detektori — `/api/check` (MoveNet) qo'shildi.
- ✅ PostgreSQL: API kalitlar va billing qo'shildi.
- ✅ Rasm natijasini R2/S3'ga saqlash — `StorageService` qo'shildi.
- ✅ Rate limit Redis bilan (ko'p server uchun) — `RateLimiterService` yangilandi.
- ✅ Connect oqimi (OAuth-uslubida CMS plugin kalit ulash) qo'shildi.
- Modal tarafida secret tekshiruvini yoqish.
