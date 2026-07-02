package uz.tryon.api.admin;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import uz.tryon.api.wallet.CreditTransaction;
import uz.tryon.api.wallet.CreditTransactionRepository;

import java.util.List;
import java.util.UUID;

@Service
public class ModerationService {

    private final CreditTransactionRepository txRepo;

    public ModerationService(CreditTransactionRepository txRepo) {
        this.txRepo = txRepo;
    }

    @Transactional
    public CreditTransaction setStatus(UUID id, String status) {
        CreditTransaction tx = txRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tranzaksiya topilmadi."));
        tx.setModerationStatus(status);
        return txRepo.save(tx);
    }

    public List<Object[]> list(String filter, int limit, int offset) {
        String status = statusForFilter(filter);
        return txRepo.findModerationRows(status, limit, offset);
    }

    public long count(String filter) {
        String status = statusForFilter(filter);
        return txRepo.countModerationRows(status);
    }

    /** "all" → null (no filter); "flagged" → "FLAGGED"; "hidden" → "HIDDEN". */
    private String statusForFilter(String filter) {
        if (filter == null || filter.isBlank() || "all".equalsIgnoreCase(filter)) return null;
        if ("flagged".equalsIgnoreCase(filter)) return "FLAGGED";
        if ("hidden".equalsIgnoreCase(filter)) return "HIDDEN";
        return null;
    }
}
