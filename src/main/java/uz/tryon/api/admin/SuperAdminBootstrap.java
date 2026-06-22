package uz.tryon.api.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uz.tryon.api.auth.Client;
import uz.tryon.api.auth.ClientRepository;
import uz.tryon.api.auth.Phones;

import java.util.Optional;

/**
 * Ishga tushganda super-admin rolini tayinlaydi.
 *
 * tryon.super-admin-phone (env: TRYON_SUPER_ADMIN_PHONE yoki SUPER_ADMIN_PHONE) o'rnatilgan
 * VA shu telefonli mijoz mavjud bo'lsa — uning roli SUPER_ADMIN ga o'rnatiladi (idempotent).
 * Kodda telefon raqami yozilmaydi. O'rnatilmagan/topilmasa — faqat log, hech narsa qilinmaydi.
 */
@Component
public class SuperAdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SuperAdminBootstrap.class);

    private final ClientRepository clients;
    private final String superAdminPhone;

    public SuperAdminBootstrap(ClientRepository clients,
                               @Value("${tryon.super-admin-phone:}") String superAdminPhone) {
        this.clients = clients;
        this.superAdminPhone = superAdminPhone;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        // application.yml dagi default bo'sh bo'lsa, env'ni to'g'ridan-to'g'ri ham tekshiramiz.
        String phone = (superAdminPhone == null || superAdminPhone.isBlank())
                ? System.getenv("SUPER_ADMIN_PHONE")
                : superAdminPhone;

        if (phone == null || phone.isBlank()) {
            log.info("Super-admin telefoni o'rnatilmagan (TRYON_SUPER_ADMIN_PHONE/SUPER_ADMIN_PHONE) — o'tkazib yuborildi.");
            return;
        }

        String normalized = Phones.normalize(phone.trim());
        Optional<Client> found = clients.findByPhone(normalized);
        if (found.isEmpty()) {
            log.info("Super-admin telefoni ({}) bo'yicha mijoz topilmadi — hech narsa qilinmadi.", normalized);
            return;
        }

        Client c = found.get();
        if ("SUPER_ADMIN".equals(c.getRole())) {
            log.info("Mijoz {} allaqachon super-admin — o'zgartirish kerak emas.", normalized);
            return;
        }

        c.setRole("SUPER_ADMIN");
        clients.save(c);
        log.info("Mijoz {} super-admin sifatida belgilandi.", normalized);
    }
}
