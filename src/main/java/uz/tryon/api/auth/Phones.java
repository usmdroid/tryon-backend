package uz.tryon.api.auth;

/** Telefon raqamni bir xil ko'rinishga keltirish (OTP va akkaunt bir kalitda bo'lsin). */
public final class Phones {
    private Phones() { }

    public static String normalize(String phone) {
        return phone == null ? "" : phone.replaceAll("[\\s\\-()]", "");
    }
}
