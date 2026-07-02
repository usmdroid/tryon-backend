package uz.tryon.api.monitoring;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Umumiy statistika endpointi — autentifikatsiya talab qilinmaydi.
 * GET /api/public/stats → { partners, tryOns, months, uptime }
 * Ma'lumotlar 5 daqiqa davomida keshlanadi (PublicStatsService ichida).
 */
@RestController
@RequestMapping("/api/public")
public class PublicStatsController {

    private final PublicStatsService service;

    public PublicStatsController(PublicStatsService service) {
        this.service = service;
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> stats() {
        PublicStatsService.StatsSnapshot s = service.stats();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("partners", s.partners());
        body.put("tryOns", s.tryOns());
        body.put("months", s.months());
        body.put("uptime", s.uptime());
        return ResponseEntity.ok(body);
    }
}
