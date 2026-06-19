package uz.tryon.api;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ommaviy narxlar (auth shart emas).
 *   GET /api/pricing
 */
@RestController
@RequestMapping("/api")
public class PricingController {

    @GetMapping("/pricing")
    public ResponseEntity<?> pricing() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("usdToSim", 100);
        body.put("freeGrantSim", 100);
        body.put("tiers", List.of(
                tier(1000, 1.0),
                tier(10000, 0.95),
                tier(null, 0.9)
        ));
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    private Map<String, Object> tier(Integer uptoRequests, double simPerRequest) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("uptoRequests", uptoRequests);
        m.put("simPerRequest", simPerRequest);
        return m;
    }
}
