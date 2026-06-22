package uz.tryon.api.admin;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.tryon.api.auth.Client;
import uz.tryon.api.auth.ClientRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Super-admin amallarining transaksiyali yordamchisi (mijoz holatini o'zgartirish).
 * Kredit qo'shish CreditService.adminCreditSim() da (hamyon qulfi bilan).
 */
@Service
public class AdminService {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_SUSPENDED = "SUSPENDED";

    private final ClientRepository clients;

    public AdminService(ClientRepository clients) {
        this.clients = clients;
    }

    /** Mijozni to'xtatadi (SUSPENDED). Mijoz bo'lmasa — bo'sh Optional. */
    @Transactional
    public Optional<Client> suspend(UUID clientId) {
        return setStatus(clientId, STATUS_SUSPENDED);
    }

    /** Mijozni faollashtiradi (ACTIVE). Mijoz bo'lmasa — bo'sh Optional. */
    @Transactional
    public Optional<Client> activate(UUID clientId) {
        return setStatus(clientId, STATUS_ACTIVE);
    }

    private Optional<Client> setStatus(UUID clientId, String status) {
        Optional<Client> c = clients.findById(clientId);
        c.ifPresent(client -> {
            client.setStatus(status);
            clients.save(client);
        });
        return c;
    }
}
