package tn.esprit.marketsim.user.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import jakarta.servlet.http.HttpServletRequest;
import tn.esprit.marketsim.agent.AgentConfigRepository;
import tn.esprit.marketsim.progression.TraderGameRepository;
import tn.esprit.marketsim.progression.TraderProfileRepository;
import tn.esprit.marketsim.security.JwtService;
import tn.esprit.marketsim.user.Role;
import tn.esprit.marketsim.user.User;
import tn.esprit.marketsim.user.UserRepository;
import tn.esprit.marketsim.user.activity.ActivityLogRepository;
import tn.esprit.marketsim.user.activity.ActivityLogService;
import tn.esprit.marketsim.user.dto.AuthDtos.AuthResponse;

class AccountServiceTest {

    private static final String SECRET = "Y2xlLWRlLXRlc3QtdW5pcXVlbWVudC1wYXMtdW4tc2VjcmV0LW1hcmtldHNpbSEh"; // cle de test, pas un secret

    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final List<AccountToken> tokens = new ArrayList<>();
    private UserRepository users;
    private SessionService sessions;
    private MailService mail;
    private TotpService totp;
    private JwtService jwt;
    private AccountService service;
    private User user;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        sessions = mock(SessionService.class);
        mail = mock(MailService.class);
        totp = new TotpService();
        jwt = new JwtService(SECRET, 3_600_000);
        AccountTokenRepository tokenRepo = mock(AccountTokenRepository.class);
        when(tokenRepo.save(any())).thenAnswer(i -> {
            AccountToken t = i.getArgument(0);
            if (!tokens.contains(t)) tokens.add(t);
            return t;
        });
        when(tokenRepo.findByTokenHash(anyString())).thenAnswer(i -> tokens.stream()
                .filter(t -> t.getTokenHash().equals(i.getArgument(0))).findFirst());
        when(tokenRepo.findByUserIdAndPurposeAndUsedAtIsNull(any(), any())).thenAnswer(i -> tokens.stream()
                .filter(t -> t.getUserId().equals(i.getArgument(0)) && t.getPurpose() == i.getArgument(1) && t.getUsedAt() == null)
                .toList());
        UserSession session = UserSession.builder().id("sid-1").userId(1L).build();
        when(sessions.open(any(), any())).thenReturn(session);

        user = User.builder().id(1L).firstName("Yasmine").lastName("S").email("y@test.tn")
                .password(encoder.encode("secret1")).role(Role.USER).enabled(true).build();
        when(users.findByEmail("y@test.tn")).thenReturn(Optional.of(user));
        when(users.findById(1L)).thenReturn(Optional.of(user));

        service = new AccountService(users, encoder, jwt, sessions, totp, mail, tokenRepo,
                mock(ActivityLogService.class), mock(ActivityLogRepository.class), mock(TraderProfileRepository.class),
                mock(TraderGameRepository.class), mock(AgentConfigRepository.class), mock(HttpServletRequest.class),
                mock(tn.esprit.marketsim.notification.NotificationService.class),
                mock(tn.esprit.marketsim.user.profile.AccountDataCleaner.class));
    }

    private String mailedToken() {
        ArgumentCaptor<String> link = ArgumentCaptor.forClass(String.class);
        verify(mail).send(eq("y@test.tn"), anyString(), anyString(), link.capture(), anyString());
        return link.getValue().substring(link.getValue().indexOf("token=") + 6);
    }

    @Test
    void passwordResetWorksOnceAndLogsOutEverywhere() {
        service.requestPasswordReset("y@test.tn");
        String token = mailedToken();
        assertTrue(tokens.get(0).getTokenHash().length() == 64 && !tokens.get(0).getTokenHash().equals(token),
                "seul le hash est stocke");

        service.resetPassword(token, "nouveau12");
        assertTrue(encoder.matches("nouveau12", user.getPassword()));
        verify(sessions).revokeAll(1L, null, "PASSWORD_RESET");
        assertThrows(IllegalArgumentException.class, () -> service.resetPassword(token, "encore12"), "lien a usage unique");
    }

    @Test
    void unknownEmailSendsNothing() {
        service.requestPasswordReset("personne@test.tn");
        verify(mail, never()).send(anyString(), anyString(), anyString(), any(), anyString());
    }

    @Test
    void emailVerificationConfirmsTheAccount() {
        user.setEmailVerified(false);
        service.sendVerification(user);
        service.verifyEmail(mailedToken());
        assertTrue(user.isEmailConfirmed());
    }

    @Test
    void twoFactorLoginNeedsAValidCode() {
        var setup = service.setupTwoFactor(user);
        String now = TotpService.code(setup.secret(), System.currentTimeMillis() / 1000 / 30);
        service.enableTwoFactor(user, now);
        assertTrue(user.isTwoFactorOn());

        AuthResponse step1 = service.issue(user);
        assertTrue(step1.twoFactorRequired());
        assertNull(step1.token(), "pas de jeton avant le code");
        verify(sessions, never()).open(any(), any());

        assertThrows(IllegalArgumentException.class, () -> service.completeTwoFactor(step1.challenge(), "000000"));
        AuthResponse step2 = service.completeTwoFactor(step1.challenge(), now);
        assertNotNull(step2.token());
        assertEquals("sid-1", jwt.parse(step2.token()).get(JwtService.SESSION_CLAIM, String.class));
    }

    @Test
    void loginWithoutTwoFactorOpensASession() {
        AuthResponse r = service.issue(user);
        assertFalse(r.twoFactorRequired());
        assertEquals("sid-1", jwt.parse(r.token()).get(JwtService.SESSION_CLAIM, String.class));
    }

    @Test
    void deletingTheAccountRequiresThePasswordAndAnonymises() {
        assertThrows(IllegalArgumentException.class, () -> service.deleteOwnAccount(user, "mauvais"));
        service.deleteOwnAccount(user, "secret1");
        assertFalse(user.isEnabled());
        assertTrue(user.getDeleted());
        assertTrue(user.getEmail().endsWith("@deleted.marketsim.local"));
        assertEquals("Compte", user.getFirstName());
        verify(sessions).revokeAll(eq(1L), isNull(), eq("ACCOUNT_DELETED"));
    }

    @Test
    void adminResetGivesATemporaryPassword() {
        User admin = User.builder().id(99L).role(Role.ADMIN).build();
        var r = service.adminResetPassword(admin, 1L);
        assertEquals(12, r.temporaryPassword().length());
        assertTrue(encoder.matches(r.temporaryPassword(), user.getPassword()));
    }
}
