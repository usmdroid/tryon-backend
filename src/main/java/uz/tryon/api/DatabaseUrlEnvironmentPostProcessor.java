package uz.tryon.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

/**
 * Railway'ning DATABASE_URL (postgres://user:pass@host:port/db) o'zgaruvchisini
 * Spring datasource xossalariga (jdbc url + user + pass) aylantiradi.
 *
 * Shunda Railway'da backend servisiga FAQAT bitta o'zgaruvchi ulansa kifoya:
 *   DATABASE_URL = ${{Postgres.DATABASE_URL}}   (yoki DATABASE_PRIVATE_URL — ichki tarmoq)
 *
 * Agar DATABASE_URL bo'lmasa — application.yml dagi default (PG* / localhost) ishlatiladi.
 */
public class DatabaseUrlEnvironmentPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
        // Ichki (private) URL'ni afzal ko'ramiz — tashqariga chiqmaydi, tekin
        String raw = firstNonBlank(
                env.getProperty("DATABASE_PRIVATE_URL"),
                env.getProperty("DATABASE_URL"));
        if (raw == null) return;
        if (!raw.startsWith("postgres://") && !raw.startsWith("postgresql://")) return;

        try {
            URI uri = new URI(raw);
            String user = "", pass = "";
            String userInfo = uri.getUserInfo();
            if (userInfo != null) {
                int c = userInfo.indexOf(':');
                user = c >= 0 ? userInfo.substring(0, c) : userInfo;
                pass = c >= 0 ? userInfo.substring(c + 1) : "";
            }
            int port = uri.getPort() > 0 ? uri.getPort() : 5432;
            String path = uri.getPath();
            String db = (path != null && path.length() > 1) ? path.substring(1) : "";
            String jdbc = "jdbc:postgresql://" + uri.getHost() + ":" + port + "/" + db;

            Map<String, Object> props = new HashMap<>();
            props.put("spring.datasource.url", jdbc);
            props.put("spring.datasource.username", user);
            props.put("spring.datasource.password", pass);
            // Eng yuqori ustunlik — application.yml defaultini ustidan yozadi
            env.getPropertySources().addFirst(new MapPropertySource("railwayDatabaseUrl", props));
        } catch (Exception ignored) {
            // Format noto'g'ri bo'lsa — default datasource ishlatiladi
        }
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }
}
