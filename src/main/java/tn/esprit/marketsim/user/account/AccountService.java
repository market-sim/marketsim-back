package tn.esprit.marketsim.user.account;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.agent.AgentConfigRepository;
import tn.esprit.marketsim.common.ConflictException;
import tn.esprit.marketsim.common.NotFoundException;
import tn.esprit.marketsim.progression.TraderGameRepository;
import tn.esprit.marketsim.progression.TraderProfileRepository;
import tn.esprit.marketsim.security.JwtService;
import tn.esprit.marketsim.user.Role;
import tn.esprit.marketsim.user.User;
import tn.esprit.marketsim.user.UserRepository;
import tn.esprit.marketsim.user.activity.ActivityAction;
import tn.esprit.marketsim.user.activity.ActivityLog;
import tn.esprit.marketsim.user.activity.ActivityLogRepository;
import tn.esprit.marketsim.user.activity.ActivityLogService;
import tn.esprit.marketsim.user.activity.ActivityOutcome;
import tn.esprit.marketsim.user.dto.AuthDtos.AuthResponse;
import tn.esprit.marketsim.user.account.dto.AccountDtos.AccountStatus;
import tn.esprit.marketsim.user.account.dto.AccountDtos.AdminCreateUserRequest;
import tn.esprit.marketsim.user.account.dto.AccountDtos.SessionView;
import tn.esprit.marketsim.user.account.dto.AccountDtos.TemporaryPasswordResponse;
import tn.esprit.marketsim.user.account.dto.AccountDtos.TwoFactorSetupResponse;

/**
 * Securite du compte (module 1) : connexion en deux etapes, mot de passe oublie, confirmation d'e-mail,
 * sessions, double authentification, export et suppression des donnees personnelles, outils de l'administrateur.
 */
@Service
@RequiredArgsConstructor
public class AccountService {

    static final Duration RESET_TTL = Duration.ofMinutes(30);
    static final Duration VERIFY_TTL = Duration.ofHours(48);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final SessionService sessionService;
    private final TotpService totpService;
    private final MailService mailService;
    private final AccountTokenRepository tokenRepository;
    private final ActivityLogService activityLogService;
    private final ActivityLogRepository activityLogRepository;
    private final TraderProfileRepository traderProfileRepository;
    private final TraderGameRepository traderGameRepository;
    private final AgentConfigRepository agentConfigRepository;
    private final HttpServletRequest request;
    private final tn.esprit.marketsim.notification.NotificationService notifications;
    /** photo, passkeys et preferences : exportees et supprimees avec le compte */
    private final tn.esprit.marketsim.user.profile.AccountDataCleaner profileData;

    @Value("${app.frontend-url:http://localhost:5173}")
    private String frontendUrl;

    // ------------------------------------------------------------------ connexion

    /**
     * Fin d'une connexion reussie : ouvre une session et renvoie le jeton, ou, si la double authentification
     * est active, renvoie un jeton d'etape 2 qui attend le code a 6 chiffres.
     */
    @Transactional
    public AuthResponse issue(User user) {
        if (user.isTwoFactorOn()) {
            return new AuthResponse(null, null, true, jwtService.generateTwoFactorChallenge(user));
        }
        return openSession(user);
    }

    /**
     * Connexion par passkey : l'appareil a deja verifie l'identite (visage, empreinte ou code) en plus de la
     * possession de la cle. C'est deja une authentification forte : pas de deuxieme etape TOTP.
     */
    @Transactional
    public AuthResponse issueAfterPasskey(User user) {
        return openSession(user);
    }

    @Transactional
    public AuthResponse completeTwoFactor(String challenge, String code) {
        String email;
        try {
            email = jwtService.extractTwoFactorEmail(challenge);
        } catch (Exception e) {
            throw new IllegalArgumentException("Étape expirée : reconnecte-toi avec ton mot de passe.");
        }
        User user = userRepository.findByEmail(email)
                .filter(User::isEnabled)
                .filter(u -> !u.isTemporarilyLocked())
                .orElseThrow(() -> new IllegalArgumentException("Compte indisponible"));
        if (!totpService.verify(user.getTotpSecret(), code)) {
            activityLogService.recordImmediate(user.getId(), user.getId(), ActivityAction.TWO_FACTOR_LOGIN,
                    ActivityOutcome.FAILURE, "INVALID_CODE");
            throw new IllegalArgumentException("Code de double authentification incorrect");
        }
        activityLogService.recordAfterCommit(user.getId(), user.getId(), ActivityAction.TWO_FACTOR_LOGIN, "OK");
        return openSession(user);
    }

    private AuthResponse openSession(User user) {
        // alerte « nouvelle connexion » si cet appareil n'a jamais servi (pas pour la toute premiere connexion)
        String device = SessionService.describe(request == null ? null : request.getHeader("User-Agent"));
        boolean newDevice = sessionService.hasHistory(user.getId()) && !sessionService.knownDevice(user.getId(), device);
        UserSession s = sessionService.open(user.getId(), request);
        if (newDevice) {
            notifications.notify(user.getId(), tn.esprit.marketsim.notification.Notification.Type.NEW_DEVICE,
                    "Nouvelle connexion : " + device,
                    "Ton compte vient d'être ouvert depuis un appareil inconnu. Si ce n'est pas toi, déconnecte-le et change ton mot de passe.",
                    "/security");
        }
        return new AuthResponse(jwtService.generateToken(user, s.getId()), user);
    }

    @Transactional
    public void logout(User user) {
        String sid = SessionService.currentSessionId();
        if (sid != null) sessionService.revoke(user.getId(), sid, "LOGOUT");
        activityLogService.recordAfterCommit(user.getId(), user.getId(), ActivityAction.LOGOUT, "OK");
    }

    // ------------------------------------------------------------------ sessions

    @Transactional(readOnly = true)
    public List<SessionView> sessions(User user) {
        String current = SessionService.currentSessionId();
        return sessionService.active(user.getId()).stream()
                .map(s -> new SessionView(s.getId(), s.getDevice(), s.getIpAddress(), s.getCreatedAt(),
                        s.getLastSeenAt(), s.getExpiresAt(), s.getId().equals(current)))
                .toList();
    }

    @Transactional
    public void revokeSession(User user, String sessionId) {
        if (!sessionService.revoke(user.getId(), sessionId, "USER_REVOKED")) {
            throw new NotFoundException("Session introuvable");
        }
        activityLogService.recordAfterCommit(user.getId(), user.getId(), ActivityAction.SESSION_REVOKE, "OK");
    }

    @Transactional
    public int revokeOtherSessions(User user) {
        int n = sessionService.revokeAll(user.getId(), SessionService.currentSessionId(), "USER_REVOKED_OTHERS");
        activityLogService.recordAfterCommit(user.getId(), user.getId(), ActivityAction.SESSION_REVOKE, "OTHERS");
        return n;
    }

    @Transactional(readOnly = true)
    public AccountStatus status(User user) {
        return new AccountStatus(user.isEmailConfirmed(), user.isTwoFactorOn(), sessionService.active(user.getId()).size());
    }

    // ------------------------------------------------------------------ mot de passe oublie

    /** Toujours la meme reponse, que l'e-mail existe ou non (on ne revele pas quels comptes existent). */
    @Transactional
    public void requestPasswordReset(String email) {
        User user = userRepository.findByEmail(email.trim()).orElse(null);
        if (user == null || Boolean.TRUE.equals(user.getDeleted())) {
            activityLogService.recordImmediate(null, null, ActivityAction.PASSWORD_RESET_REQUEST,
                    ActivityOutcome.FAILURE, "UNKNOWN_ACCOUNT");
            return;
        }
        String token = newToken(user.getId(), AccountToken.Purpose.RESET_PASSWORD, RESET_TTL);
        String link = frontendUrl + "/reset-password?token=" + token;
        mailService.send(user.getEmail(), "Réinitialise ton mot de passe MarketSim AI",
                "Bonjour " + user.getFirstName() + ",\n\nQuelqu'un (probablement toi) a demandé à réinitialiser ton mot de passe. "
                        + "Ce lien est valable 30 minutes et ne fonctionne qu'une fois :\n" + link
                        + "\n\nSi ce n'est pas toi, ignore ce message : ton mot de passe ne change pas.",
                link, "RESET");
        activityLogService.recordAfterCommit(user.getId(), user.getId(), ActivityAction.PASSWORD_RESET_REQUEST, "OK");
    }

    @Transactional
    public void resetPassword(String token, String newPassword) {
        tn.esprit.marketsim.user.PasswordPolicy.check(newPassword);
        AccountToken t = consume(token, AccountToken.Purpose.RESET_PASSWORD);
        User user = userRepository.findById(t.getUserId()).orElseThrow(() -> new IllegalArgumentException("Lien invalide"));
        user.setPassword(passwordEncoder.encode(newPassword));
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        // ouvrir le lien prouve aussi l'acces a la boite mail
        if (Boolean.FALSE.equals(user.getEmailVerified())) user.setEmailVerified(true);
        userRepository.save(user);
        sessionService.revokeAll(user.getId(), null, "PASSWORD_RESET");
        notifications.notify(user.getId(), tn.esprit.marketsim.notification.Notification.Type.PASSWORD_CHANGED,
                "Mot de passe réinitialisé", "Ton mot de passe a été changé par le lien envoyé par e-mail. Toutes tes sessions ont été déconnectées.",
                "/security");
        activityLogService.recordAfterCommit(user.getId(), user.getId(), ActivityAction.PASSWORD_RESET, "OK");
    }

    // ------------------------------------------------------------------ confirmation d'e-mail

    @Transactional
    public void sendVerification(User user) {
        if (user.isEmailConfirmed()) return;
        String token = newToken(user.getId(), AccountToken.Purpose.VERIFY_EMAIL, VERIFY_TTL);
        String link = frontendUrl + "/verify-email?token=" + token;
        mailService.send(user.getEmail(), "Confirme ton adresse e-mail MarketSim AI",
                "Bienvenue " + user.getFirstName() + " !\n\nConfirme ton adresse e-mail en ouvrant ce lien (valable 48 h) :\n"
                        + link,
                link, "VERIFY");
    }

    @Transactional
    public void resendVerification(User user) {
        if (user.isEmailConfirmed()) throw new ConflictException("Ton adresse e-mail est déjà confirmée.");
        sendVerification(user);
    }

    @Transactional
    public void verifyEmail(String token) {
        AccountToken t = consume(token, AccountToken.Purpose.VERIFY_EMAIL);
        User user = userRepository.findById(t.getUserId()).orElseThrow(() -> new IllegalArgumentException("Lien invalide"));
        user.setEmailVerified(true);
        userRepository.save(user);
        activityLogService.recordAfterCommit(user.getId(), user.getId(), ActivityAction.EMAIL_VERIFY, "OK");
        notifications.notify(user.getId(), tn.esprit.marketsim.notification.Notification.Type.INFO, "Adresse e-mail confirmée ✓",
                "Ton compte est vérifié : tu recevras les alertes importantes à cette adresse.", "/profile");
    }

    /** Message d'accueil dans la cloche, juste apres l'inscription. */
    public void welcome(User user) {
        notifications.notify(user.getId(), tn.esprit.marketsim.notification.Notification.Type.INFO,
                "Bienvenue sur MarketSim AI, " + user.getFirstName() + " ! 🎉",
                "Tu commences avec 100 000 $ virtuels (un seul portefeuille pour le jeu et le trading). Visite l'Académie pour apprendre en jouant, puis bats l'agent IA dans le Jeu de trading.",
                "/education");
    }

    // ------------------------------------------------------------------ double authentification

    /** Etape 1 : genere un secret (pas encore actif) a scanner dans l'application d'authentification. */
    @Transactional
    public TwoFactorSetupResponse setupTwoFactor(User user) {
        User u = reload(user);
        if (u.isTwoFactorOn()) throw new ConflictException("La double authentification est déjà active.");
        String secret = totpService.newSecret();
        u.setTotpSecret(secret);
        u.setTotpEnabled(false);
        userRepository.save(u);
        return new TwoFactorSetupResponse(secret, totpService.otpauthUri(secret, u.getEmail()));
    }

    /** Etape 2 : le premier code valide prouve que l'application est bien configuree. */
    @Transactional
    public void enableTwoFactor(User user, String code) {
        User u = reload(user);
        if (u.getTotpSecret() == null) throw new IllegalArgumentException("Commence par générer le QR code.");
        if (!totpService.verify(u.getTotpSecret(), code)) throw new IllegalArgumentException("Code incorrect");
        u.setTotpEnabled(true);
        userRepository.save(u);
        notifications.notify(u.getId(), tn.esprit.marketsim.notification.Notification.Type.TWO_FACTOR,
                "Double authentification activée", "Un code à 6 chiffres sera demandé à chaque connexion.", "/security");
        activityLogService.recordAfterCommit(u.getId(), u.getId(), ActivityAction.TWO_FACTOR_ENABLE, "OK");
    }

    @Transactional
    public void disableTwoFactor(User user, String password, String code) {
        User u = reload(user);
        if (!u.isTwoFactorOn()) throw new ConflictException("La double authentification n'est pas active.");
        if (!passwordEncoder.matches(password, u.getPassword())) throw new IllegalArgumentException("Mot de passe incorrect");
        if (!totpService.verify(u.getTotpSecret(), code)) throw new IllegalArgumentException("Code incorrect");
        u.setTotpEnabled(false);
        u.setTotpSecret(null);
        userRepository.save(u);
        activityLogService.recordAfterCommit(u.getId(), u.getId(), ActivityAction.TWO_FACTOR_DISABLE, "OK");
        notifications.notify(u.getId(), tn.esprit.marketsim.notification.Notification.Type.TWO_FACTOR,
                "Double authentification désactivée", "Si ce n'est pas toi, change ton mot de passe tout de suite.", "/security");
    }

    // ------------------------------------------------------------------ donnees personnelles

    /** Toutes les donnees de l'utilisateur, au format JSON (droit d'acces / portabilite). */
    @Transactional(readOnly = true)
    public Map<String, Object> export(User user) {
        User u = reload(user);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("exportedAt", Instant.now());
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("id", u.getId());
        profile.put("firstName", u.getFirstName());
        profile.put("lastName", u.getLastName());
        profile.put("email", u.getEmail());
        profile.put("role", u.getRole());
        profile.put("level", u.getLevel());
        profile.put("createdAt", u.getCreatedAt());
        profile.put("emailConfirmed", u.isEmailConfirmed());
        profile.put("twoFactorEnabled", u.isTwoFactorOn());
        out.put("profile", profile);
        out.put("tradingProfile", traderProfileRepository.findByUserId(u.getId()).orElse(null));
        out.put("profileExtras", profileData.export(u.getId()));
        out.put("games", traderGameRepository.findTop20ByUserIdOrderByPlayedAtDesc(u.getId()));
        List<Map<String, Object>> agents = new ArrayList<>();
        agentConfigRepository.findByOwnerId(u.getId()).forEach(a -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.getId());
            m.put("name", a.getName());
            m.put("strategy", a.getStrategy());
            m.put("riskLevel", a.getRiskLevel());
            m.put("allowedAssets", a.getAllowedAssets());
            m.put("createdAt", a.getCreatedAt());
            agents.add(m);
        });
        out.put("agents", agents);
        out.put("sessions", sessionService.active(u.getId()).stream()
                .map(s -> Map.of("device", String.valueOf(s.getDevice()), "createdAt", s.getCreatedAt(),
                        "lastSeenAt", s.getLastSeenAt()))
                .toList());
        Specification<ActivityLog> mine = (root, q, cb) -> cb.or(cb.equal(root.get("actorId"), u.getId()),
                cb.equal(root.get("targetUserId"), u.getId()));
        out.put("activity", activityLogRepository.findAll(mine,
                        PageRequest.of(0, 500, Sort.by(Sort.Direction.DESC, "occurredAt"))).getContent().stream()
                .map(l -> Map.of("occurredAt", l.getOccurredAt(), "action", l.getAction(), "outcome", l.getOutcome(),
                        "reason", l.getReasonCode()))
                .toList());
        activityLogService.recordAfterCommit(u.getId(), u.getId(), ActivityAction.ACCOUNT_EXPORT, "OK");
        return out;
    }

    /**
     * Suppression du compte par son proprietaire : les donnees personnelles sont effacees (nom, e-mail,
     * mot de passe, double authentification), le compte est desactive et toutes ses sessions sont coupees.
     * Les donnees de simulation restent rattachees a un compte anonyme pour garder l'historique coherent.
     */
    @Transactional
    public void deleteOwnAccount(User user, String password) {
        User u = reload(user);
        if (!passwordEncoder.matches(password, u.getPassword())) throw new IllegalArgumentException("Mot de passe incorrect");
        if (u.getRole() == Role.ADMIN && userRepository.countByRoleAndEnabled(Role.ADMIN, true) <= 1) {
            throw new ConflictException("Tu es le dernier administrateur actif : nomme un autre admin avant de supprimer ton compte.");
        }
        u.setFirstName("Compte");
        u.setLastName("supprimé");
        u.setEmail("supprime-" + u.getId() + "-" + Long.toHexString(RANDOM.nextLong()) + "@deleted.marketsim.local");
        u.setPassword(passwordEncoder.encode(randomPassword(24)));
        u.setTotpEnabled(false);
        u.setTotpSecret(null);
        u.setAvatarKey(null);
        u.setEnabled(false);
        u.setDeleted(true);
        userRepository.save(u);
        tokenRepository.deleteAll(tokenRepository.findByUserId(u.getId()));
        sessionService.revokeAll(u.getId(), null, "ACCOUNT_DELETED");
        profileData.delete(u.getId());
        activityLogService.recordAfterCommit(u.getId(), u.getId(), ActivityAction.ACCOUNT_DELETE, "OK");
    }

    // ------------------------------------------------------------------ administrateur

    @Transactional
    public TemporaryPasswordResponse adminCreate(User admin, AdminCreateUserRequest req) {
        String email = req.email().trim();
        if (userRepository.existsByEmail(email)) throw new ConflictException("Cet email est déjà utilisé");
        boolean generated = req.password() == null || req.password().isBlank();
        if (!generated) tn.esprit.marketsim.user.PasswordPolicy.check(req.password());
        String password = generated ? randomPassword(12) : req.password();
        User user = User.builder()
                .firstName(req.firstName().trim())
                .lastName(req.lastName().trim())
                .email(email)
                .password(passwordEncoder.encode(password))
                .role(req.role() == null ? Role.USER : req.role())
                .enabled(true)
                .emailVerified(true)
                .build();
        userRepository.save(user);
        mailService.send(email, "Ton compte MarketSim AI a été créé",
                "Bonjour " + user.getFirstName() + ",\n\nUn administrateur a créé ton compte MarketSim AI.\n"
                        + (generated ? "Mot de passe provisoire : " + password + "\n" : "")
                        + "Connecte-toi ici : " + frontendUrl + "/login\nPense à changer ton mot de passe dans ton profil.",
                frontendUrl + "/login", "WELCOME");
        activityLogService.recordAfterCommit(admin.getId(), user.getId(), ActivityAction.ADMIN_CREATE_USER, "OK");
        return new TemporaryPasswordResponse(user.getId(), email, generated ? password : null,
                generated ? "Compte créé : transmets ce mot de passe provisoire à l'utilisateur, il ne sera plus affiché."
                        : "Compte créé avec le mot de passe choisi.");
    }

    /** Nouveau mot de passe provisoire, sessions coupees, compte deverrouille. */
    @Transactional
    public TemporaryPasswordResponse adminResetPassword(User admin, Long userId) {
        User user = userRepository.findById(userId).orElseThrow(() -> new NotFoundException("Utilisateur introuvable"));
        if (Boolean.TRUE.equals(user.getDeleted())) throw new ConflictException("Ce compte a été supprimé.");
        String password = randomPassword(12);
        user.setPassword(passwordEncoder.encode(password));
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        userRepository.save(user);
        int cut = sessionService.revokeAll(user.getId(), user.getId().equals(admin.getId())
                ? SessionService.currentSessionId() : null, "ADMIN_RESET_PASSWORD");
        mailService.send(user.getEmail(), "Ton mot de passe MarketSim AI a été réinitialisé",
                "Bonjour " + user.getFirstName() + ",\n\nUn administrateur a réinitialisé ton mot de passe.\n"
                        + "Mot de passe provisoire : " + password + "\nConnecte-toi puis change-le dans ton profil.",
                frontendUrl + "/login", "ADMIN_RESET");
        activityLogService.recordAfterCommit(admin.getId(), user.getId(), ActivityAction.ADMIN_RESET_PASSWORD, "OK");
        notifications.notify(user.getId(), tn.esprit.marketsim.notification.Notification.Type.PASSWORD_CHANGED,
                "Mot de passe réinitialisé par l'administrateur", "Connecte-toi avec le mot de passe provisoire reçu, puis change-le dans ton profil.",
                "/profile");
        return new TemporaryPasswordResponse(user.getId(), user.getEmail(), password,
                "Mot de passe réinitialisé, " + cut + " session(s) déconnectée(s). Transmets-le à l'utilisateur.");
    }

    @Transactional
    public int adminRevokeSessions(User admin, Long userId) {
        if (!userRepository.existsById(userId)) throw new NotFoundException("Utilisateur introuvable");
        String keep = userId.equals(admin.getId()) ? SessionService.currentSessionId() : null;
        int n = sessionService.revokeAll(userId, keep, "ADMIN_REVOKED");
        activityLogService.recordAfterCommit(admin.getId(), userId, ActivityAction.ADMIN_REVOKE_SESSIONS, "OK");
        return n;
    }

    @Transactional(readOnly = true)
    public List<SessionView> adminSessions(Long userId) {
        return sessionService.active(userId).stream()
                .map(s -> new SessionView(s.getId(), s.getDevice(), s.getIpAddress(), s.getCreatedAt(),
                        s.getLastSeenAt(), s.getExpiresAt(), false))
                .toList();
    }

    // ------------------------------------------------------------------ outils

    private User reload(User user) {
        return userRepository.findById(user.getId()).orElseThrow(() -> new NotFoundException("Utilisateur introuvable"));
    }

    /** Cree un jeton a usage unique (les precedents du meme type sont annules) et renvoie sa valeur en clair. */
    private String newToken(Long userId, AccountToken.Purpose purpose, Duration ttl) {
        Instant now = Instant.now();
        for (AccountToken old : tokenRepository.findByUserIdAndPurposeAndUsedAtIsNull(userId, purpose)) {
            old.setUsedAt(now);
            tokenRepository.save(old);
        }
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        AccountToken t = new AccountToken();
        t.setUserId(userId);
        t.setPurpose(purpose);
        t.setTokenHash(sha256(token));
        t.setCreatedAt(now);
        t.setExpiresAt(now.plus(ttl));
        tokenRepository.save(t);
        return token;
    }

    private AccountToken consume(String token, AccountToken.Purpose purpose) {
        AccountToken t = tokenRepository.findByTokenHash(sha256(token == null ? "" : token.trim()))
                .filter(x -> x.getPurpose() == purpose)
                .orElseThrow(() -> new IllegalArgumentException("Lien invalide"));
        if (t.getUsedAt() != null) throw new IllegalArgumentException("Ce lien a déjà été utilisé");
        if (t.getExpiresAt().isBefore(Instant.now())) throw new IllegalArgumentException("Ce lien a expiré : refais une demande");
        t.setUsedAt(Instant.now());
        tokenRepository.save(t);
        return t;
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Mot de passe lisible sans caracteres ambigus (0/O, 1/l). */
    static String randomPassword(int length) {
        String alphabet = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) sb.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        // le mot de passe provisoire respecte lui aussi la regle (au moins une lettre et un chiffre)
        if (tn.esprit.marketsim.user.PasswordPolicy.problem(sb.toString()) != null) return randomPassword(length);
        return sb.toString();
    }
}
