package tn.esprit.marketsim.agent.autopilot;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Planificateur du pilote automatique : chaque seconde, fait avancer les sessions dont c'est le tour. */
@Slf4j
@Configuration
@EnableScheduling
@RequiredArgsConstructor
public class AutopilotScheduler {

    private final AutopilotService autopilotService;

    @Scheduled(fixedDelay = 1000, initialDelay = 5000)
    public void run() {
        for (Long id : autopilotService.dueSessionIds()) {
            try {
                autopilotService.tick(id);
            } catch (Exception e) {
                log.error("Pilote automatique : session {} arretee sur erreur", id, e);
                autopilotService.fail(id, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
        }
    }
}
