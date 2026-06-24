package uz.tryon.api.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Do'kon (hamkor) akkaunti. Identifikator: telefon (majburiy) yoki email (ixtiyoriy). */
@Entity
@Table(name = "clients")
public class Client {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String name;

    /** Majburiy, unik. */
    @Column(unique = true)
    private String phone;

    /** Ixtiyoriy, agar berilsa unik. */
    @Column(unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    /** Rol: CLIENT (oddiy hamkor) yoki SUPER_ADMIN (super-admin). Default — CLIENT. */
    @Column(name = "role", nullable = false, length = 20)
    private String role = "CLIENT";

    /** Holat: ACTIVE (faol) yoki SUSPENDED (to'xtatilgan). Default — ACTIVE. */
    @Column(name = "status", nullable = false, length = 20)
    private String status = "ACTIVE";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Client() { }

    public Client(String name, String phone, String email, String passwordHash) {
        this.name = name;
        this.phone = phone;
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = "CLIENT";
        this.status = "ACTIVE";
    }

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
        if (role == null) role = "CLIENT";
        if (status == null) status = "ACTIVE";
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getPhone() { return phone; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public String getRole() { return role; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }

    /** Rolni o'zgartirish (faqat @Transactional servis metodlari ichida ishlatiladi). */
    public void setRole(String role) { this.role = role; }

    /** Holatni o'zgartirish (faqat @Transactional servis metodlari ichida ishlatiladi). */
    public void setStatus(String status) { this.status = status; }

    /** Telefon raqamni o'zgartirish (faqat @Transactional servis metodlari ichida ishlatiladi). */
    public void setPhone(String phone) { this.phone = phone; }

    /** Email manzilni o'zgartirish (faqat @Transactional servis metodlari ichida ishlatiladi). */
    public void setEmail(String email) { this.email = email; }
}
