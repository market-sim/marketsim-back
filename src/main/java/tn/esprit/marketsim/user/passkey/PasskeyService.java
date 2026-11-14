package tn.esprit.marketsim.user.passkey;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

import tn.esprit.marketsim.notification.Notification;
import tn.esprit.marketsim.notification.NotificationService;
import tn.esprit.marketsim.user.AuthService;
import tn.esprit.marketsim.user.User;
import tn.esprit.marketsim.user.UserRepository;
import tn.esprit.marketsim.user.activity.ActivityAction;
import tn.esprit.marketsim.user.activity.ActivityLogService;
import tn.esprit.marketsim.user.activity.ActivityOutcome;
import tn.esprit.marketsim.user.dto.AuthDtos.AuthResponse;
import tn.esprit.marketsim.user.passkey.ChallengeStore.Purpose;
import tn.esprit.marketsim.user.passkey.WebAuthn.WebAuthnException;
import tn.esprit.marketsim.user.profile.PreferencesService;
import tn.esprit.marketsim.user.profile.UserPreferences;
import tn.esprit.marketsim.user.profile.UserPreferencesRepository;

/**
 * Passkeys : se connecter sans mot de passe avec Face ID, Touch ID, l'empreinte du telephone ou Windows Hello.
 * L'appareil verifie l'identite (biometrie ou code) et signe un defi ; le serveur verifie la signature avec la
 * cle publique enregistree.
 */
@Service
public class PasskeyService {

    static final int MAX_PER_USER = 10;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final PasskeyRepository repository;
    private final ChallengeStore challenges;
    private final PreferencesService preferences;
    private final UserPreferencesRepository preferencesRepository;
    private final UserRepository userRepository;
    private final AuthService authService;
    private final ActivityLogService activityLogService;
    private final NotificationService notifications;
    private final String rpId;
    private final String rpName;
    private final Set<String> origins;

    public PasskeyService(PasskeyRepository repository, ChallengeStore challenges, PreferencesService preferences,
                          UserPreferencesRepository preferencesRepository, UserRepository userRepository,
                          AuthService authService, ActivityLogService activityLogService, NotificationService notifications,
                          @Value("${app.webauthn.rp-id:localhost}") String rpId,
                          @Value("${app.webauthn.rp-name:MarketSim AI}") String rpName,
                          @Value("${app.webauthn.origins:http://localhost:5173}") String origins) {
        this.repository = repository;
        this.challenges = challenges;
        this.preferences = preferences;
        this.preferencesRepository = preferencesRepository;
        this.userRepository = userRepository;
        this.authService = authService;
        this.activityLogService = activityLogService;
        this.notifications = notifications;
        this.rpId = rpId;
        this.rpName = rpName;
        this.origins = Arrays.stream(origins.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
    }

    public record RegisterRequest(String id, String clientDataJSON, String attestationObject, List<String> transports, String name) {
    }

    public record LoginRequest(String id, String clientDataJSON, String authenticatorData, String signature, String userHandle) {
    }

    // ─── enregistrement ──────────────────────────────────────────────────────────────────────

    public Map<String, Object> registrationOptions(User user) {
        UserPreferences prefs = preferences.get(user.getId());
        List<Map<String, Object>> exclude = repository.findByUserIdOrderByCreatedAtDesc(user.getId()).stream()
                .map(c -> Map.<String, Object>of("type", "public-key", "id", c.getCredentialId()))
                .toList();
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("challenge", challenges.issue(Purpose.REGISTER, user.getId()));
        o.put("rp", Map.of("id", rpId, "name", rpName));
        o.put("user", Map.of("id", prefs.getWebauthnHandle(), "name", user.getEmail(),
                "displayName", (user.getFirstName() + " " + user.getLastName()).trim()));
        o.put("pubKeyCredParams", List.of(Map.of("type", "public-key", "alg", WebAuthn.ALG_ES256),
                Map.of("type", "public-key", "alg", WebAuthn.ALG_RS256)));
        o.put("timeout", 60000);
        o.put("attestation", "none");
        o.put("authenticatorSelection", Map.of("residentKey", "required", "requireResidentKey", true, "userVerification", "required"));
        o.put("excludeCredentials", exclude);
        return o;
    }

    @Transactional
    public PasskeyCredential register(User user, RegisterRequest req) {
        if (repository.countByUserId(user.getId()) >= MAX_PER_USER) {
            throw new IllegalArgumentException("Tu as déjà " + MAX_PER_USER + " passkeys : supprimes-en une d'abord.");
        }
        byte[] clientData = WebAuthn.fromB64url(required(req.clientDataJSON()));
        String challenge = challengeOf(clientData);
        if (!challenges.consume(challenge, Purpose.REGISTER, user.getId())) {
            throw new WebAuthnException("Demande expirée : recommence l'ajout de la passkey.");
        }
        WebAuthn.Registration reg = WebAuthn.verifyRegistration(clientData, WebAuthn.fromB64url(required(req.attestationObject())),
                challenge, rpId, origins);
        String credentialId = WebAuthn.b64url(reg.credentialId());
        if (req.id() != null && !req.id().equals(credentialId)) throw new WebAuthnException("Identifiant de passkey incohérent");
        if (repository.findByCredentialId(credentialId).isPresent()) throw new WebAuthnException("Cette passkey est déjà enregistrée");

        PasskeyCredential c = new PasskeyCredential();
        c.setUserId(user.getId());
        c.setCredentialId(credentialId);
        c.setPublicKey(reg.key().x509());
        c.setAlg(reg.key().alg());
        c.setSignCount(reg.signCount());
        c.setBackedUp(reg.backedUp());
        c.setName(cleanName(req.name()));
        c.setTransports(req.transports() == null ? null
                : String.join(",", req.transports().stream().filter(t -> t.matches("[a-z-]{2,20}")).limit(6).toList()));
        c.setCreatedAt(Instant.now());
        repository.save(c);
        activityLogService.recordAfterCommit(user.getId(), user.getId(), ActivityAction.PASSKEY_REGISTER, "OK");
        notifications.notify(user.getId(), Notification.Type.TWO_FACTOR, "Passkey ajoutée : " + c.getName(),
                "Tu peux maintenant te connecter sans mot de passe avec cet appareil. Si ce n'est pas toi, supprime-la tout de suite.",
                "/security");
        return c;
    }

    @Transactional(readOnly = true)
    public List<PasskeyCredential> list(User user) {
        return repository.findByUserIdOrderByCreatedAtDesc(user.getId());
    }

    @Transactional
    public PasskeyCredential rename(User user, Long id, String name) {
        PasskeyCredential c = owned(user, id);
        c.setName(cleanName(name));
        return repository.save(c);
    }

    @Transactional
    public void delete(User user, Long id) {
        PasskeyCredential c = owned(user, id);
        repository.delete(c);
        activityLogService.recordAfterCommit(user.getId(), user.getId(), ActivityAction.PASSKEY_DELETE, "OK");
        notifications.notify(user.getId(), Notification.Type.TWO_FACTOR, "Passkey supprimée : " + c.getName(),
                "Cet appareil ne peut plus ouvrir ton compte sans mot de passe.", "/security");
    }

    // ─── connexion ───────────────────────────────────────────────────────────────────────────

    public Map<String, Object> loginOptions() {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("challenge", challenges.issue(Purpose.LOGIN, null));
        o.put("rpId", rpId);
        o.put("timeout", 60000);
        o.put("userVerification", "required");
        o.put("allowCredentials", List.of());
        return o;
    }

    @Transactional
    public AuthResponse login(LoginRequest req) {
        byte[] clientData = WebAuthn.fromB64url(required(req.clientDataJSON()));
        String challenge = challengeOf(clientData);
        if (!challenges.consume(challenge, Purpose.LOGIN, null)) {
            throw new WebAuthnException("Demande expirée : réessaie la connexion avec ta passkey.");
        }
        PasskeyCredential c = repository.findByCredentialId(required(req.id())).orElse(null);
        if (c == null) {
            activityLogService.recordImmediate(null, null, ActivityAction.PASSKEY_LOGIN, ActivityOutcome.FAILURE, "UNKNOWN_PASSKEY");
            throw new WebAuthnException("Cette passkey n'est liée à aucun compte. Connecte-toi avec ton mot de passe puis ajoute-la dans Sécurité.");
        }
        if (req.userHandle() != null && !req.userHandle().isBlank()) {
            Long owner = preferencesRepository.findByWebauthnHandle(req.userHandle()).map(UserPreferences::getUserId).orElse(null);
            if (!c.getUserId().equals(owner)) throw new WebAuthnException("Passkey et compte ne correspondent pas");
        }
        long count;
        try {
            count = WebAuthn.verifyAssertion(clientData, WebAuthn.fromB64url(required(req.authenticatorData())),
                    WebAuthn.fromB64url(required(req.signature())), challenge, rpId, origins,
                    new WebAuthn.StoredKey(c.getPublicKey(), c.getAlg()), c.getSignCount());
        } catch (WebAuthnException e) {
            activityLogService.recordImmediate(c.getUserId(), c.getUserId(), ActivityAction.PASSKEY_LOGIN, ActivityOutcome.FAILURE,
                    "INVALID_ASSERTION");
            throw e;
        }
        c.setSignCount(count);
        c.setLastUsedAt(Instant.now());
        repository.save(c);
        User user = userRepository.findById(c.getUserId()).orElseThrow(() -> new WebAuthnException("Compte introuvable"));
        return authService.loginWithPasskey(user, c.getName());
    }

    // ─── utilitaires ─────────────────────────────────────────────────────────────────────────

    private PasskeyCredential owned(User user, Long id) {
        return repository.findById(id).filter(c -> c.getUserId().equals(user.getId()))
                .orElseThrow(() -> new IllegalArgumentException("Passkey introuvable"));
    }

    static String challengeOf(byte[] clientData) {
        try {
            String c = JSON.readTree(new String(clientData, StandardCharsets.UTF_8)).path("challenge").asText(null);
            if (c == null || c.isBlank()) throw new WebAuthnException("Défi absent");
            return c;
        } catch (WebAuthnException e) {
            throw e;
        } catch (Exception e) {
            throw new WebAuthnException("clientDataJSON illisible");
        }
    }

    static String cleanName(String name) {
        String n = name == null ? "" : name.replaceAll("[\\p{Cntrl}<>]", "").strip();
        if (n.isEmpty()) return "Mon appareil";
        return n.length() > 60 ? n.substring(0, 60) : n;
    }

    private static String required(String s) {
        if (s == null || s.isBlank()) throw new WebAuthnException("Réponse de l'appareil incomplète");
        return s;
    }
}
