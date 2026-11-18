package tn.esprit.marketsim.analysis.fundamental;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Limite de 10 requetes par seconde vers la SEC, verifiee avec une horloge simulee (aucune vraie attente). */
class SecRateLimiterTest {

    private long now;
    private final List<Long> sleeps = new ArrayList<>();

    private SecRateLimiter limiter() {
        return new SecRateLimiter(() -> now, nanos -> {
            sleeps.add(nanos);
            now += nanos;
        });
    }

    @Test
    void theFirstRequestLeavesAtOnce() throws InterruptedException {
        limiter().acquire();
        assertEquals(List.of(), sleeps);
    }

    @Test
    void requestsInABurstAreSpacedBy100Milliseconds() throws InterruptedException {
        SecRateLimiter limiter = limiter();
        long start = now;
        for (int i = 0; i < 11; i++) limiter.acquire();
        // 11 requetes d'affilee : la 1re part tout de suite, chacune des 10 suivantes attend 100 ms
        assertEquals(10, sleeps.size());
        sleeps.forEach(s -> assertEquals(100_000_000L, s));
        assertEquals(1_000_000_000L, now - start, "11 requetes prennent 1 s : jamais plus de 10 par seconde");
    }

    @Test
    void aRequestAfterAPauseDoesNotWait() throws InterruptedException {
        SecRateLimiter limiter = limiter();
        limiter.acquire();
        now += 250_000_000L;        // 250 ms plus tard
        limiter.acquire();
        now += 40_000_000L;         // 40 ms apres : il reste 60 ms a attendre
        limiter.acquire();
        assertEquals(List.of(60_000_000L), sleeps);
    }
}
