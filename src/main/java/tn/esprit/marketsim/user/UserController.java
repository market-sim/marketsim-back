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
import tn.esprit.marketsim.user.activity.ActivityAction;
import tn.esprit.marketsim.user.activity.ActivityLogService;
import tn.esprit.marketsim.user.activity.ActivityOutcome;
import tn.esprit.marketsim.user.activity.TraceDetails;
import tn.esprit.marketsim.user.dto.AuthDtos.UpdateProfileRequest;
import tn.esprit.marketsim.user.dto.AuthDtos.UserProfileResponse;
import tn.esprit.marketsim.user.score.SecurityScoreService;
import tn.esprit.marketsim.user.score.dto.SecurityScoreDtos.SecurityScoreResponse;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class UserController {

    private final UserRepository userRepository;
    private final UserService userService;
    private final ActivityLogService activityLogService;
    private final SecurityScoreService securityScoreService;

    @GetMapping("/users/me")
    public User me(@AuthenticationPrincipal User user) {
        return user;
    }

    @GetMapping("/users/me/security-score")
    public SecurityScoreResponse mySecurityScore(@AuthenticationPrincipal User user) {
        return securityScoreService.recomputeOwn(user.getId());
    }

    @PatchMapping("/users/me")
    public UserProfileResponse updateProfile(@AuthenticationPrincipal User user, @Valid @RequestBody UpdateProfileRequest req) {
        return userService.updateProfile(user.getId(), req);
    }

    @PutMapping("/users/me")
    public User updateNames(@AuthenticationPrincipal User user, @Valid @RequestBody UpdateProfileRequest req) {
        userService.updateProfile(user.getId(), req);
        return userRepository.findById(user.getId()).orElseThrow(() -> new NotFoundException("Utilisateur introuvable"));
    }

    @GetMapping("/admin/users")
    public List<User> all() {
        return userRepository.findAll();
    }

    @GetMapping("/admin/users/{userId}/security-score")
    public SecurityScoreResponse userSecurityScore(@PathVariable Long userId) {
        return securityScoreService.recomputeForAdmin(userId);
    }

    @PatchMapping("/admin/users/{id}/enabled")
    public User setEnabled(
            @AuthenticationPrincipal User admin,
            @PathVariable Long id,
            @RequestParam boolean value) {
        User user = userRepository.findById(id).orElse(null);
        if (user == null) {
            activityLogService.recordImmediate(
                    admin.getId(), id, ActivityAction.ADMIN_SET_ENABLED, ActivityOutcome.FAILURE, "USER_NOT_FOUND",
                    TraceDetails.empty());
            throw new NotFoundException("Utilisateur introuvable");
        }
        boolean previous = user.isEnabled();
        user.setEnabled(value);
        User saved = userRepository.save(user);
        TraceDetails details = TraceDetails.empty().add("enabled", previous, value);
        activityLogService.recordAfterCommit(
                admin.getId(),
                id,
                ActivityAction.ADMIN_SET_ENABLED,
                value ? "ENABLED" : "DISABLED",
                details);
        return saved;
    }

    @DeleteMapping("/admin/users/{id}")
    public void delete(@AuthenticationPrincipal User admin, @PathVariable Long id) {
        if (!userRepository.existsById(id)) {
            activityLogService.recordImmediate(
                    admin.getId(), id, ActivityAction.ADMIN_DELETE_USER, ActivityOutcome.FAILURE, "USER_NOT_FOUND",
                    TraceDetails.empty());
            throw new NotFoundException("Utilisateur introuvable");
        }
        userRepository.deleteById(id);
        TraceDetails details = TraceDetails.empty().add("accountDeleted", false, true);
        activityLogService.recordAfterCommit(admin.getId(), id, ActivityAction.ADMIN_DELETE_USER, "OK", details);
    }
}
