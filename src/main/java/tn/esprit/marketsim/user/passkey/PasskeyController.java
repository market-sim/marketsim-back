package tn.esprit.marketsim.user.passkey;

import java.util.List;
import java.util.Map;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.user.User;
import tn.esprit.marketsim.user.dto.AuthDtos.AuthResponse;

/** Passkeys : gestion (connecte) et connexion sans mot de passe (public, sous /api/auth). */
@RestController
@RequiredArgsConstructor
public class PasskeyController {

    private final PasskeyService service;

    public record RenameRequest(String name) {
    }

    @PostMapping("/api/account/passkeys/options")
    public Map<String, Object> registrationOptions(@AuthenticationPrincipal User user) {
        return service.registrationOptions(user);
    }

    @PostMapping("/api/account/passkeys")
    public PasskeyCredential register(@RequestBody PasskeyService.RegisterRequest req, @AuthenticationPrincipal User user) {
        return service.register(user, req);
    }

    @GetMapping("/api/account/passkeys")
    public List<PasskeyCredential> list(@AuthenticationPrincipal User user) {
        return service.list(user);
    }

    @PatchMapping("/api/account/passkeys/{id}")
    public PasskeyCredential rename(@PathVariable Long id, @RequestBody RenameRequest req, @AuthenticationPrincipal User user) {
        return service.rename(user, id, req.name());
    }

    @DeleteMapping("/api/account/passkeys/{id}")
    public void delete(@PathVariable Long id, @AuthenticationPrincipal User user) {
        service.delete(user, id);
    }

    @PostMapping("/api/auth/passkey/options")
    public Map<String, Object> loginOptions() {
        return service.loginOptions();
    }

    @PostMapping("/api/auth/passkey/login")
    public AuthResponse login(@RequestBody PasskeyService.LoginRequest req) {
        return service.login(req);
    }
}
