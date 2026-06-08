package uz.tryon.api;

/**
 * Bitta tekshiruv natijasi (frontend uni log/Toast'ga aylantiradi).
 *
 * status:
 *   "pass" — o'tdi
 *   "warn" — yaroqli, lekin sifat past bo'lishi mumkin (rad etilmaydi)
 *   "fail" — rad etiladi (generatsiyaga yaroqsiz)
 *   "skip" — bu bosqichda hali tekshirilmaydi (ML keyin qo'shiladi)
 */
public record CheckItem(String id, String label, String status, String message) {

    public static final String PASS = "pass";
    public static final String WARN = "warn";
    public static final String FAIL = "fail";
    public static final String SKIP = "skip";

    public static CheckItem pass(String id, String label, String message) {
        return new CheckItem(id, label, PASS, message);
    }

    public static CheckItem warn(String id, String label, String message) {
        return new CheckItem(id, label, WARN, message);
    }

    public static CheckItem fail(String id, String label, String message) {
        return new CheckItem(id, label, FAIL, message);
    }

    public static CheckItem skip(String id, String label, String message) {
        return new CheckItem(id, label, SKIP, message);
    }

    public boolean isFail() { return FAIL.equals(status); }
}
