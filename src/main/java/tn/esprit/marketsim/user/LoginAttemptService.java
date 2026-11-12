package tn.esprit.marketsim.user;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.common.NotFoundException;
import tn.esprit.marketsim.user.settings.GlobalUserSettings;
import tn.esprit.marketsim.user.settings.GlobalUserSettingsService;

/** Persiste les echecs de connexion dans une transaction separee (non annulee par l'echec du login). */
@Service
@RequiredArgsConstructor
public class LoginAttemptService {

    private final UserRepository userRepository;
    private final GlobalUserSettingsService settingsService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void registerFailedAttempt(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("Utilisateur introuvable"));
        GlobalUserSettings settings = settingsService.getOrCreate();
        int failures = user.getFailedLoginAttempts() + 1;
        user.setFailedLoginAttempts(failures);
        if (failures >= settings.getMaxFailedLoginAttempts()) {
            user.setLockedUntil(LocalDateTime.now().plusMinutes(settings.getLockDurationMinutes()));
            user.setFailedLoginAttempts(0);
        }
        userRepository.saveAndFlush(user);
    }
}
