package uz.tryon.api.monitoring;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import uz.tryon.api.auth.ClientRepository;
import uz.tryon.api.wallet.CreditTransactionRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * GET /api/public/stats: 200 shakli va kesh xatti-harakati.
 *
 * HTTP shakl testi: SpringBootTest + MockMvc (haqiqiy kontekst).
 * Kesh testi: PublicStatsService bevosita Mockito mock'lar bilan (unit darajada).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PublicStatsTest {

    @Autowired
    MockMvc mvc;

    // ── HTTP shape test ────────────────────────────────────────────────────────

    @Test
    void stats_200_correctShape() throws Exception {
        mvc.perform(get("/api/public/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partners").isNumber())
                .andExpect(jsonPath("$.tryOns").isNumber())
                .andExpect(jsonPath("$.months").isNumber())
                .andExpect(jsonPath("$.uptime").isNumber());
    }

    @Test
    void stats_noAuthRequired() throws Exception {
        // Hech qanday Authorization headersiz 200 qaytishi shart
        mvc.perform(get("/api/public/stats"))
                .andExpect(status().isOk());
    }

    // ── Cache unit tests ───────────────────────────────────────────────────────

    private ClientRepository mockClients;
    private CreditTransactionRepository mockTxns;
    private PublicStatsService svc;

    @BeforeEach
    void setUpUnit() {
        mockClients = mock(ClientRepository.class);
        mockTxns = mock(CreditTransactionRepository.class);
        svc = new PublicStatsService(mockClients, mockTxns);
    }

    @Test
    void stats_cachedOnSecondCall() {
        when(mockClients.countByStatus("ACTIVE")).thenReturn(4L);
        when(mockTxns.countAllDebits()).thenReturn(200L);

        svc.stats();
        svc.stats(); // second call — keshdan kelishi kerak

        // DB faqat bir marta chaqirilishi shart
        verify(mockClients, times(1)).countByStatus("ACTIVE");
        verify(mockTxns, times(1)).countAllDebits();
    }

    @Test
    void stats_fallbackFloorWhenNoPartners() {
        when(mockClients.countByStatus("ACTIVE")).thenReturn(0L);
        when(mockTxns.countAllDebits()).thenReturn(0L);

        PublicStatsService.StatsSnapshot s = svc.stats();

        assertThat(s.partners()).isEqualTo(3);
        assertThat(s.tryOns()).isEqualTo(100L);
        assertThat(s.months()).isEqualTo(6);
        assertThat(s.uptime()).isEqualTo(99.5);
    }

    @Test
    void stats_realDataWhenPartnersExist() {
        when(mockClients.countByStatus("ACTIVE")).thenReturn(5L);
        when(mockTxns.countAllDebits()).thenReturn(1234L);

        PublicStatsService.StatsSnapshot s = svc.stats();

        assertThat(s.partners()).isEqualTo(5);
        assertThat(s.tryOns()).isEqualTo(1234L);
        assertThat(s.months()).isEqualTo(6);
        assertThat(s.uptime()).isEqualTo(99.8);
    }
}
