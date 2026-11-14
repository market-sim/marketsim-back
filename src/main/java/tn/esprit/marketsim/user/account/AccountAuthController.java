package tn.esprit.marketsim.user.account;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.security.RecaptchaService;
import tn.esprit.marketsim.user.account.dto.AccountDtos.ForgotPasswordRequest;
import tn.esprit.marketsim.user.account.dto.AccountDtos.MessageResponse;
import tn.esprit.marketsim.user.account.dto.AccountDtos.ResetPasswordRequest;
import tn.esprit.marketsim.user.account.dto.AccountDtos.TwoFactorLoginRequest;
import tn.esprit.marketsim.user.account.dto.AccountDtos.VerifyEmailRequest;
import tn.esprit.marketsim.user.dto.AuthDtos.AuthResponse;

/** Routes publiques (sans connexion) : etape 2 de la connexion, mot de passe oublie, confirmation d'e-mail. */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AccountAuthController {

    private final AccountService accountService;
    private final RecaptchaService recaptcha;

    @PostMapping("/login/2fa")
    public AuthResponse twoFactor(@Valid @RequestBody TwoFactorLoginRequest req) {
        return accountService.completeTwoFactor(req.challenge(), req.code());
    }

    @PostMapping("/forgot-password")
    public MessageResponse forgot(@Valid @RequestBody ForgotPasswordRequest req,
                                  @RequestHeader(value = RecaptchaService.HEADER, required = false) String captcha,
                                  HttpServletRequest http) {
        recaptcha.verify(captcha, http.getRemoteAddr());
        accountService.requestPasswordReset(req.email());
        return new MessageResponse("Si un compte existe pour cette adresse, un lien de réinitialisation vient d'être envoyé (valable 30 minutes).");
    }

    @PostMapping("/reset-password")
    public MessageResponse reset(@Valid @RequestBody ResetPasswordRequest req) {
        accountService.resetPassword(req.token(), req.newPassword());
        return new MessageResponse("Mot de passe modifié. Toutes tes sessions ont été déconnectées : connecte-toi avec le nouveau mot de passe.");
    }

    @PostMapping("/verify-email")
    public MessageResponse verify(@Valid @RequestBody VerifyEmailRequest req) {
        accountService.verifyEmail(req.token());
        return new MessageResponse("Adresse e-mail confirmée. Merci !");
    }
}
