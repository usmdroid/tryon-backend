-- Email endi ilova darajasida majburiy (registratsiyada talab qilinadi).
-- DB'da NOT NULL qo'shilmadi: eski yozuvlarda email null/bo'sh bo'lishi mumkin,
-- shularning xavfsizligi uchun faqat uniqueligini kafolatlaymiz.
-- V1 da email allaqachon UNIQUE bo'lgan; bu migratsiya idempotent va
-- mavjud unique cheklov/indeks bo'lsa ham xatosiz o'tishi kerak.
CREATE UNIQUE INDEX IF NOT EXISTS clients_email_unique_idx ON clients (email);
