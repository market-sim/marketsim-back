package tn.esprit.marketsim.analysis.admin;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import tn.esprit.marketsim.analysis.backtest.SimulationDefaultsService;
import tn.esprit.marketsim.analysis.fundamental.FundamentalsService;
import tn.esprit.marketsim.user.Role;
import tn.esprit.marketsim.user.User;

/**
 * Securite de l'administration du module 3, a travers la vraie chaine de securite du projet (SecurityConfig,
 * filtre JWT, {@code @PreAuthorize}) : un utilisateur ordinaire recoit 403, une requete sans jeton 401, l'ADMIN
 * passe. Les services sont simules : aucune donnee n'est lue ni modifiee.
 *
 * <p>Comme {@code MarketsimBackendApplicationTests}, ce test charge tout le contexte : MySQL doit etre demarre.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AnalysisAdminSecurityTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AnalysisAdminService service;

    @MockitoBean
    private SimulationDefaultsService defaults;

    @MockitoBean
    private FundamentalsService fundamentals;

    /** Toutes les routes d'administration du module 3. */
    static Stream<MockHttpServletRequestBuilder> adminRoutes() {
        String base = "/api/analysis/admin";
        return Stream.of(
                get(base + "/defaults"),
                put(base + "/defaults").contentType(MediaType.APPLICATION_JSON).content("{\"initialCapital\":5000}"),
                get(base + "/defaults/history"),
                get(base + "/users"),
                get(base + "/backtests").param("symbol", "AAPL"),
                get(base + "/backtests/1"),
                delete(base + "/backtests/1"),
                get(base + "/comparisons"),
                get(base + "/comparisons/1"),
                delete(base + "/comparisons/1"),
                get(base + "/stats"),
                get(base + "/fundamentals"),
                delete(base + "/fundamentals/AAPL"));
    }

    /** Authentification comme la pose le filtre JWT : notre User, avec l'autorite ROLE_{role}. */
    private static RequestPostProcessor as(Role role) {
        User user = new User();
        user.setId(role == Role.ADMIN ? 1L : 2L);
        user.setEmail(role == Role.ADMIN ? "admin@test.local" : "user@test.local");
        user.setRole(role);
        return authentication(new UsernamePasswordAuthenticationToken(user, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))));
    }

    @ParameterizedTest
    @MethodSource("adminRoutes")
    void anOrdinaryUserGets403(MockHttpServletRequestBuilder request) throws Exception {
        mvc.perform(request.with(as(Role.USER))).andExpect(status().isForbidden());
        verifyNoInteractions(service, defaults, fundamentals);
    }

    @ParameterizedTest
    @MethodSource("adminRoutes")
    void withoutTokenTheAnswerIs401(MockHttpServletRequestBuilder request) throws Exception {
        mvc.perform(request).andExpect(status().isUnauthorized());
        verifyNoInteractions(service, defaults, fundamentals);
    }

    @ParameterizedTest
    @MethodSource("adminRoutes")
    void anAdminIsAllowed(MockHttpServletRequestBuilder request) throws Exception {
        mvc.perform(request.with(as(Role.ADMIN))).andExpect(status().isOk());
    }

    @Test
    void everyConnectedUserCanReadTheDefaults() throws Exception {
        mvc.perform(get("/api/analysis/defaults").with(as(Role.USER))).andExpect(status().isOk());
    }
}
