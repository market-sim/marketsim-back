package tn.esprit.marketsim.user;

import java.time.Instant;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.security.JwtService;
import tn.esprit.marketsim.user.activity.ActivityAction;
import tn.esprit.marketsim.user.activity.ActivityLogService;
import tn.esprit.marketsim.user.activity.ActivityOutcome;
import tn.esprit.marketsim.user.activity.TraceDetails;
import tn.esprit.marketsim.user.anomaly.LoginAnomalyModel;
import tn.esprit.marketsim.user.anomaly.LoginAnomalyService;
import tn.esprit.marketsim.user.anomaly.LoginRequestContext;
import tn.esprit.marketsim.user.dto.AuthDtos.AuthResponse;
import tn.esprit.marketsim.user.dto.AuthDtos.LoginRequest;
import tn.esprit.marketsim.user.dto.AuthDtos.RegisterRequest;
import tn.esprit.marketsim.user.settings.GlobalUserSettings;
import tn.esprit.marketsim.user.settings.GlobalUserSettingsService;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final GlobalUserSettingsService settingsService;
    private final LoginAttemptService loginAttemptService;
    private final ActivityLogService activityLogService;
    private final LoginAnomalyService loginAnomalyService;
    private final LoginRequestContext loginRequestContext;

    @Transactional
    public AuthResponse register(RegisterRequest req) {
        GlobalUserSettings settings = settingsService.getOrCreate();
        if (!settings.isRegistrationEnabled()) {
            activityLogService.recordImmediate(null, null, ActivityAction.REGISTER, ActivityOutcome.FAILURE,
                    "REGISTRATION_DISABLED");
            throw new IllegalArgumentException("Les inscriptions sont temporairement desactivees");
        }
        if (userRepository.existsByEmail(req.email())) {
            activityLogService.recordImmediate(null, null, ActivityAction.REGISTER, ActivityOutcome.FAILURE,
                    "EMAIL_TAKEN");
            throw new IllegalArgumentException("Cet email est deja utilise");
        }
        User user = User.builder()
                .firstName(req.firstName())
                .lastName(req.lastName())
                .email(req.email())
                .password(passwordEncoder.encode(req.password()))
                .role(Role.USER)
                .enabled(true)
                .build();
        userRepository.save(user);
        activityLogService.recordAfterCommit(user.getId(), user.getId(), ActivityAction.REGISTER, "OK");
        return new AuthResponse(jwtService.generateToken(user), user);
    }

    @Transactional
    public AuthResponse login(LoginRequest req) {
        Instant attemptAt = Instant.now();
        String userAgent = loginRequestContext.userAgent();
        String deviceHint = loginRequestContext.deviceHint();
        TraceDetails browserMeta = loginBrowserDetails(userAgent);

        User user = userRepository.findByEmail(req.email()).orElse(null);
        if (user == null) {
            Long logId = activityLogService.recordImmediate(null, null, ActivityAction.LOGIN, ActivityOutcome.FAILURE,
                    "UNKNOWN_ACCOUNT", browserMeta, attemptAt);
            loginAnomalyService.assessLogin(logId, null, true, userAgent, deviceHint, attemptAt);
            throw new IllegalArgumentException("Email ou mot de passe incorrect");
        }

        if (user.isTemporarilyLocked()) {
            Long logId = activityLogService.recordImmediate(user.getId(), user.getId(), ActivityAction.LOGIN,
                    ActivityOutcome.FAILURE, "ACCOUNT_LOCKED", browserMeta, attemptAt);
            loginAnomalyService.assessLogin(logId, user.getId(), true, userAgent, deviceHint, attemptAt);
            throw new IllegalArgumentException(
                    "Compte temporairement verrouille jusqu'a " + user.getLockedUntil());
        }

        if (!passwordEncoder.matches(req.password(), user.getPassword())) {
            loginAttemptService.registerFailedAttempt(user.getId());
            Long logId = activityLogService.recordImmediate(user.getId(), user.getId(), ActivityAction.LOGIN,
                    ActivityOutcome.FAILURE, "INVALID_CREDENTIALS", browserMeta, attemptAt);
            loginAnomalyService.assessLogin(logId, user.getId(), true, userAgent, deviceHint, attemptAt);
            throw new IllegalArgumentException("Email ou mot de passe incorrect");
        }

        if (!user.isEnabled()) {
            Long logId = activityLogService.recordImmediate(user.getId(), user.getId(), ActivityAction.LOGIN,
                    ActivityOutcome.FAILURE, "ACCOUNT_DISABLED", browserMeta, attemptAt);
            loginAnomalyService.assessLogin(logId, user.getId(), true, userAgent, deviceHint, attemptAt);
            throw new IllegalArgumentException("Compte desactive");
        }

        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        userRepository.save(user);
        Long userId = user.getId();
        activityLogService.recordAfterCommit(userId, userId, ActivityAction.LOGIN, "OK", browserMeta,
                logId -> loginAnomalyService.assessLogin(logId, userId, false, userAgent, deviceHint, attemptAt),
                attemptAt);
        return new AuthResponse(jwtService.generateToken(user), user);
    }

    private static TraceDetails loginBrowserDetails(String userAgent) {
        String family = LoginAnomalyModel.normalizeBrowserFamily(userAgent);
        return TraceDetails.empty().add("browserFamily", null, family);
    }
}
