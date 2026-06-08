package uz.tryon.api;

import java.util.List;

/**
 * Rasm tekshiruvining to'liq natijasi — POST /api/check javobi.
 *
 *   ok        — true bo'lsa rasm generatsiyaga yaroqli (hech qaysi tekshiruv "fail" emas)
 *   clothType — qaysi kiyim turi uchun tekshirildi (upper/lower/full)
 *   checks    — har bir tekshiruv natijasi (frontend log/Toast uchun)
 *   summary   — umumiy qisqa xulosa (Toast'da ko'rsatish uchun)
 */
public record CheckReport(boolean ok, String clothType, List<CheckItem> checks, String summary) {

    /** checks ro'yxatidan ok va summary'ni avtomatik hisoblab report quradi. */
    public static CheckReport of(String clothType, List<CheckItem> checks) {
        List<CheckItem> failed = checks.stream().filter(CheckItem::isFail).toList();
        boolean ok = failed.isEmpty();
        String summary = ok
                ? "Rasm generatsiyaga yaroqli."
                : "Rasm yaroqsiz: " + failed.get(0).message();
        return new CheckReport(ok, clothType, checks, summary);
    }
}
