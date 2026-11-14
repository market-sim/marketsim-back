package tn.esprit.marketsim.user.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SessionServiceTest {

    private final Map<String, UserSession> store = new HashMap<>();
    private SessionService service;

    @BeforeEach
    void setUp() {
        UserSessionRepository repo = mock(UserSessionRepository.class);
        when(repo.save(any())).thenAnswer(i -> {
            UserSession s = i.getArgument(0);
            store.put(s.getId(), s);
            return s;
        });
        when(repo.findById(any())).thenAnswer(i -> Optional.ofNullable(store.get((String) i.getArgument(0))));
        when(repo.findByUserIdAndRevokedAtIsNullOrderByLastSeenAtDesc(any())).thenAnswer(i -> {
            List<UserSession> out = new ArrayList<>();
            store.values().forEach(s -> {
                if (s.getUserId().equals(i.getArgument(0)) && s.getRevokedAt() == null) out.add(s);
            });
            return out;
        });
        service = new SessionService(repo, 3_600_000);
    }

    @Test
    void openedSessionIsActiveUntilRevoked() {
        UserSession s = service.open(7L, null);
        assertTrue(service.touchIfActive(s.getId(), 7L));
        assertFalse(service.touchIfActive(s.getId(), 8L), "la session d'un autre utilisateur est refusee");
        assertTrue(service.revoke(7L, s.getId(), "LOGOUT"));
        assertFalse(service.touchIfActive(s.getId(), 7L), "apres deconnexion le jeton ne marche plus");
        assertFalse(service.revoke(7L, s.getId(), "LOGOUT"), "on ne revoque pas deux fois");
    }

    @Test
    void expiredSessionIsRefused() {
        UserSession s = service.open(7L, null);
        s.setExpiresAt(Instant.now().minusSeconds(1));
        assertFalse(service.touchIfActive(s.getId(), 7L));
        assertTrue(service.active(7L).isEmpty());
    }

    @Test
    void revokeAllKeepsTheCurrentSession() {
        UserSession a = service.open(7L, null);
        UserSession b = service.open(7L, null);
        UserSession c = service.open(7L, null);
        service.open(9L, null);
        assertEquals(2, service.revokeAll(7L, b.getId(), "OTHERS"));
        assertFalse(service.touchIfActive(a.getId(), 7L));
        assertTrue(service.touchIfActive(b.getId(), 7L));
        assertFalse(service.touchIfActive(c.getId(), 7L));
        assertEquals(1, service.active(9L).size(), "les sessions des autres utilisateurs ne bougent pas");
    }

    @Test
    void describesTheDevice() {
        assertEquals("Chrome · Windows", SessionService.describe(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"));
        assertEquals("Appareil inconnu", SessionService.describe(null));
    }
}
