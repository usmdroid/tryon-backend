-- Super-admin + RBAC: clients jadvaliga rol va holat ustunlari.
-- role:   CLIENT (oddiy hamkor) yoki SUPER_ADMIN (super-admin).
-- status: ACTIVE (faol) yoki SUSPENDED (to'xtatilgan).
-- Mavjud mijozlar default qiymatlarni oladi (CLIENT / ACTIVE).
ALTER TABLE clients ADD COLUMN role   VARCHAR(20) NOT NULL DEFAULT 'CLIENT';
ALTER TABLE clients ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE';

-- Eslatma: V6 dagi credit_transactions.type ustunida CHECK cheklov yo'q
-- (faqat VARCHAR(32)), shuning uchun 'ADMIN_CREDIT' qiymati uchun qo'shimcha
-- o'zgartirish kerak emas.
