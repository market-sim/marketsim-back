package tn.esprit.marketsim.user.settings;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.common.ConflictException;
import tn.esprit.marketsim.user.User;
import tn.esprit.marketsim.user.activity.ActivityAction;
import tn.esprit.marketsim.user.activity.ActivityLogService;
import tn.esprit.marketsim.user.activity.ActivityOutcome;
import tn.esprit.marketsim.user.activity.TraceDetails;
import tn.esprit.marketsim.user.settings.dto.GlobalUserSettingsDtos.GlobalUserSettingsResponse;
import tn.esprit.marketsim.user.settings.dto.GlobalUserSettingsDtos.UpdateGlobalUserSettingsRequest;

@Service
@RequiredArgsConstructor
public class GlobalUserSettingsService {

    private final GlobalUserSettingsRepository repository;
    private final ActivityLogService activityLogService;

    @Transactional
    public GlobalUserSettings getOrCreate() {
        return repository.findById(GlobalUserSettings.SINGLETON_ID)
                .orElseGet(() -> repository.save(GlobalUserSettings.defaults()));
    }

    @Transactional
    public GlobalUserSettingsResponse get() {
        return toResponse(getOrCreate());
    }

    @Transactional
    public GlobalUserSettingsResponse update(UpdateGlobalUserSettingsRequest req) {
        Long actorId = currentActorId();

        try {
            validate(req.maxFailedLoginAttempts(), req.lockDurationMinutes());
        } catch (IllegalArgumentException ex) {
            failSettings(actorId, "INVALID_RANGE");
            throw ex;
        }

        GlobalUserSettings settings = getOrCreate();
        if (settings.getVersion() != req.version()) {
            failSettings(actorId, "VERSION_CONFLICT");
            throw new ConflictException("Parametres modifies ailleurs — rechargez avant de reenregistrer");
        }

        boolean prevRegistration = settings.isRegistrationEnabled();
        int prevMaxFailed = settings.getMaxFailedLoginAttempts();
        int prevLock = settings.getLockDurationMinutes();

        settings.setRegistrationEnabled(req.registrationEnabled());
        settings.setMaxFailedLoginAttempts(req.maxFailedLoginAttempts());
        settings.setLockDurationMinutes(req.lockDurationMinutes());

        try {
            GlobalUserSettings saved = repository.saveAndFlush(settings);
            TraceDetails details = TraceDetails.empty()
                    .add("registrationEnabled", prevRegistration, saved.isRegistrationEnabled())
                    .add("maxFailedLoginAttempts", prevMaxFailed, saved.getMaxFailedLoginAttempts())
                    .add("lockDurationMinutes", prevLock, saved.getLockDurationMinutes());
            activityLogService.recordAfterCommit(actorId, null, ActivityAction.SETTINGS_UPDATE, "OK", details);
            return toResponse(saved);
        } catch (OptimisticLockingFailureException ex) {
            failSettings(actorId, "VERSION_CONFLICT");
            throw new ConflictException("Parametres modifies ailleurs — rechargez avant de reenregistrer");
        }
    }

    private void failSettings(Long actorId, String reasonCode) {
        activityLogService.recordImmediate(
                actorId, null, ActivityAction.SETTINGS_UPDATE, ActivityOutcome.FAILURE, reasonCode,
                TraceDetails.empty());
    }

    private static Long currentActorId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof User user) {
            return user.getId();
        }
        return null;
    }

    static void validate(int maxFailedLoginAttempts, int lockDurationMinutes) {
        if (maxFailedLoginAttempts < 3 || maxFailedLoginAttempts > 10) {
            throw new IllegalArgumentException("Le seuil d'echecs doit etre entre 3 et 10");
        }
        if (lockDurationMinutes < 1 || lockDurationMinutes > 60) {
            throw new IllegalArgumentException("La duree de verrouillage doit etre entre 1 et 60 minutes");
        }
    }

    private static GlobalUserSettingsResponse toResponse(GlobalUserSettings settings) {
        return new GlobalUserSettingsResponse(
                settings.isRegistrationEnabled(),
                settings.getMaxFailedLoginAttempts(),
                settings.getLockDurationMinutes(),
                settings.getVersion(),
                settings.getUpdatedAt());
    }
}
