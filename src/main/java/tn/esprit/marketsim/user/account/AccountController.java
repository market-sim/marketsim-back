package tn.esprit.marketsim.user.account;

import java.util.List;
import java.util.Map;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.user.User;
import tn.esprit.marketsim.user.account.dto.AccountDtos.AccountStatus;
import tn.esprit.marketsim.user.account.dto.AccountDtos.DeleteAccountRequest;
import tn.esprit.marketsim.user.account.dto.AccountDtos.MessageResponse;
import tn.esprit.marketsim.user.account.dto.AccountDtos.SessionView;
import tn.esprit.marketsim.user.account.dto.AccountDtos.TwoFactorCodeRequest;
import tn.esprit.marketsim.user.account.dto.AccountDtos.TwoFactorDisableRequest;
import tn.esprit.marketsim.user.account.dto.AccountDtos.TwoFactorSetupResponse;

/** Securite du compte connecte : deconnexion, sessions, double authentification, donnees personnelles. */
@RestController
@RequestMapping("/api/account")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;

    @GetMapping("/status")
    public AccountStatus status(@AuthenticationPrincipal User user) {
        return accountService.status(user);
    }

    @PostMapping("/logout")
    public MessageResponse logout(@AuthenticationPrincipal User user) {
        accountService.logout(user);
        return new MessageResponse("Déconnecté.");
    }

    @GetMapping("/sessions")
    public List<SessionView> sessions(@AuthenticationPrincipal User user) {
        return accountService.sessions(user);
    }

    @DeleteMapping("/sessions/{sessionId}")
    public MessageResponse revoke(@PathVariable String sessionId, @AuthenticationPrincipal User user) {
        accountService.revokeSession(user, sessionId);
        return new MessageResponse("Session déconnectée.");
    }

    @PostMapping("/sessions/revoke-others")
    public MessageResponse revokeOthers(@AuthenticationPrincipal User user) {
        int n = accountService.revokeOtherSessions(user);
        return new MessageResponse(n + " autre(s) appareil(s) déconnecté(s).");
    }

    @PostMapping("/resend-verification")
    public MessageResponse resend(@AuthenticationPrincipal User user) {
        accountService.resendVerification(user);
        return new MessageResponse("E-mail de confirmation renvoyé.");
    }

    @PostMapping("/2fa/setup")
    public TwoFactorSetupResponse setup(@AuthenticationPrincipal User user) {
        return accountService.setupTwoFactor(user);
    }

    @PostMapping("/2fa/enable")
    public MessageResponse enable(@Valid @RequestBody TwoFactorCodeRequest req, @AuthenticationPrincipal User user) {
        accountService.enableTwoFactor(user, req.code());
        return new MessageResponse("Double authentification activée.");
    }

    @PostMapping("/2fa/disable")
    public MessageResponse disable(@Valid @RequestBody TwoFactorDisableRequest req, @AuthenticationPrincipal User user) {
        accountService.disableTwoFactor(user, req.password(), req.code());
        return new MessageResponse("Double authentification désactivée.");
    }

    @GetMapping("/export")
    public Map<String, Object> export(@AuthenticationPrincipal User user) {
        return accountService.export(user);
    }

    @PostMapping("/delete")
    public MessageResponse delete(@Valid @RequestBody DeleteAccountRequest req, @AuthenticationPrincipal User user) {
        accountService.deleteOwnAccount(user, req.password());
        return new MessageResponse("Ton compte a été supprimé.");
    }
}
