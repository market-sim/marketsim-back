package tn.esprit.marketsim.analysis.fundamental;

import java.util.function.LongSupplier;

/**
 * Module 3 : limite les appels a la SEC a {@value #MAX_PER_SECOND} requetes par seconde (regle d'acces equitable
 * de la SEC). Chaque requete attend au moins {@code 1 s / 10 = 100 ms} apres le debut de la precedente.
 * Partage par tout le serveur : deux utilisateurs qui consultent des actions en meme temps attendent leur tour.
 */
public class SecRateLimiter {

    public static final int MAX_PER_SECOND = 10;
    static final long MIN_INTERVAL_NANOS = 1_000_000_000L / MAX_PER_SECOND;

    /** Attente, remplacable dans les tests. */
    public interface Sleeper {
        void sleepNanos(long nanos) throws InterruptedException;
    }

    private final LongSupplier clock;
    private final Sleeper sleeper;
    private boolean first = true;
    /** Instant (en nanosecondes) a partir duquel la prochaine requete peut partir. */
    private long nextAllowed;

    public SecRateLimiter() {
        this(System::nanoTime, nanos -> Thread.sleep(nanos / 1_000_000, (int) (nanos % 1_000_000)));
    }

    SecRateLimiter(LongSupplier clock, Sleeper sleeper) {
        this.clock = clock;
        this.sleeper = sleeper;
    }

    /** Attend si besoin que la requete puisse partir. {@code synchronized} : une seule requete a la fois decide. */
    public synchronized void acquire() throws InterruptedException {
        long now = clock.getAsLong();
        if (!first && now < nextAllowed) {
            sleeper.sleepNanos(nextAllowed - now);
            now = nextAllowed;
        }
        first = false;
        nextAllowed = now + MIN_INTERVAL_NANOS;
    }
}
