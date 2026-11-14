package tn.esprit.marketsim.user.account;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.user.User;
import tn.esprit.marketsim.user.account.dto.AccountDtos.AdminCreateUserRequest;
import tn.esprit.marketsim.user.account.dto.AccountDtos.MailView;
import tn.esprit.marketsim.user.account.dto.AccountDtos.MessageResponse;
import tn.esprit.marketsim.user.account.dto.AccountDtos.SessionView;
import tn.esprit.marketsim.user.account.dto.AccountDtos.TemporaryPasswordResponse;

/** Outils de l'administrateur sur les comptes : creation, mot de passe provisoire, sessions, boite d'envoi. */
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminAccountController {

    private final AccountService accountService;
    private final MailService mailService;

    @PostMapping("/users/create")
    public TemporaryPasswordResponse create(@Valid @RequestBody AdminCreateUserRequest req, @AuthenticationPrincipal User admin) {
        return accountService.adminCreate(admin, req);
    }

    @PostMapping("/users/{id}/reset-password")
    public TemporaryPasswordResponse resetPassword(@PathVariable Long id, @AuthenticationPrincipal User admin) {
        return accountService.adminResetPassword(admin, id);
    }

    @GetMapping("/users/{id}/sessions")
    public List<SessionView> sessions(@PathVariable Long id) {
        return accountService.adminSessions(id);
    }

    @PostMapping("/users/{id}/revoke-sessions")
    public MessageResponse revokeSessions(@PathVariable Long id, @AuthenticationPrincipal User admin) {
        int n = accountService.adminRevokeSessions(admin, id);
        return new MessageResponse(n + " session(s) déconnectée(s).");
    }

    /** Boite d'envoi : e-mails generes par la plateforme (sans serveur SMTP en local). */
    @GetMapping("/mail-outbox")
    public List<MailView> outbox() {
        return mailService.latest().stream()
                .map(m -> new MailView(m.getId(), m.getRecipient(), m.getSubject(), m.getBody(), m.getActionUrl(),
                        m.getKind(), m.getCreatedAt(), m.getStatus() == null ? "LOCAL" : m.getStatus(), m.getError()))
                .toList();
    }

    /** Serveur SMTP configure ou non (sans jamais renvoyer le mot de passe). */
    @GetMapping("/mail-status")
    public MailService.MailStatus mailStatus() {
        return mailService.status();
    }

    /** Envoie un e-mail de test a l'administrateur connecte pour verifier la configuration SMTP. */
    @PostMapping("/mail-test")
    public MessageResponse mailTest(@AuthenticationPrincipal User admin) {
        mailService.send(admin.getEmail(), "Test d'envoi MarketSim AI",
                "Si tu lis ce message dans ta boîte (ou dans Mailtrap), l'envoi d'e-mails fonctionne. Bravo !",
                null, "TEST");
        return new MessageResponse(mailService.status().smtpConfigured()
                ? "E-mail de test envoyé à " + admin.getEmail() + " : regarde le statut dans la boîte d'envoi."
                : "Aucun serveur SMTP configuré : le message est seulement dans la boîte d'envoi.");
    }
}
