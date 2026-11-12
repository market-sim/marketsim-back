package tn.esprit.marketsim.user;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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

    @GetMapping("/registration-status")
    public RegistrationStatusResponse registrationStatus() {
        return new RegistrationStatusResponse(settingsService.getOrCreate().isRegistrationEnabled());
    }

    @PostMapping("/register")
    public AuthResponse register(@Valid @RequestBody RegisterRequest req) {
        return authService.register(req);
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest req) {
        return authService.login(req);
    }
}
