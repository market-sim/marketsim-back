package tn.esprit.marketsim.user;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.security.RecaptchaService;
import tn.esprit.marketsim.user.dto.AuthDtos.AuthResponse;
import tn.esprit.marketsim.user.dto.AuthDtos.LoginRequest;
import tn.esprit.marketsim.user.dto.AuthDtos.RegisterRequest;
import tn.esprit.marketsim.user.settings.GlobalUserSettingsService;
import tn.esprit.marketsim.user.settings.dto.GlobalUserSettingsDtos.RegistrationStatusResponse;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final GlobalUserSettingsService settingsService;
    private final RecaptchaService recaptcha;

    /** Configuration publique du reCAPTCHA (cle du site) pour afficher la case dans le navigateur. */
    public record CaptchaConfig(boolean enabled, String siteKey) {
    }

    @GetMapping("/captcha-config")
    public CaptchaConfig captchaConfig() {
        return new CaptchaConfig(recaptcha.enabled(), recaptcha.siteKey());
    }

    @GetMapping("/registration-status")
    public RegistrationStatusResponse registrationStatus() {
        return new RegistrationStatusResponse(settingsService.getOrCreate().isRegistrationEnabled());
    }

    @PostMapping("/register")
    public AuthResponse register(@Valid @RequestBody RegisterRequest req,
                                 @RequestHeader(value = RecaptchaService.HEADER, required = false) String captcha,
                                 HttpServletRequest http) {
        recaptcha.verify(captcha, http.getRemoteAddr());
        return authService.register(req);
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest req,
                              @RequestHeader(value = RecaptchaService.HEADER, required = false) String captcha,
                              HttpServletRequest http) {
        recaptcha.verify(captcha, http.getRemoteAddr());
        return authService.login(req);
    }
}
