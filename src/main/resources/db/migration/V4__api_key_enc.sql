-- To'liq API kalitni shifrlangan holda saqlash (dashboard ro'yxatidan istalgancha nusxalash uchun).
-- AES-GCM, base64(iv+ciphertext). Eski kalitlarda NULL — ular faqat keyPrefix bilan ko'rinadi.
ALTER TABLE api_keys ADD COLUMN key_enc TEXT;
