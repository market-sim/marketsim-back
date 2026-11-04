package tn.esprit.marketsim.user;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.common.NotFoundException;
import tn.esprit.marketsim.user.dto.AuthDtos.UpdateProfileRequest;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class UserController {

    private final UserRepository userRepository;

    // ----- Utilisateur connecte -----

    @GetMapping("/users/me")
    public User me(@AuthenticationPrincipal User user) {
        return user;
    }

    @PutMapping("/users/me")
    public User updateProfile(@AuthenticationPrincipal User user, @Valid @RequestBody UpdateProfileRequest req) {
        user.setFirstName(req.firstName());
        user.setLastName(req.lastName());
        return userRepository.save(user);
    }

    // ----- Back-office admin (/api/admin/** reserve au role ADMIN, voir SecurityConfig) -----

    @GetMapping("/admin/users")
    public List<User> all() {
        return userRepository.findAll();
    }

    @PatchMapping("/admin/users/{id}/enabled")
    public User setEnabled(@PathVariable Long id, @RequestParam boolean value) {
        User user = userRepository.findById(id).orElseThrow(() -> new NotFoundException("Utilisateur introuvable"));
        user.setEnabled(value);
        return userRepository.save(user);
    }

    @DeleteMapping("/admin/users/{id}")
    public void delete(@PathVariable Long id) {
        userRepository.deleteById(id);
    }
}
