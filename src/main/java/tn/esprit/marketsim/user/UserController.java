package tn.esprit.marketsim.user;

import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
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
import tn.esprit.marketsim.common.ConflictException;
import tn.esprit.marketsim.common.NotFoundException;
import tn.esprit.marketsim.user.activity.ActivityAction;
import tn.esprit.marketsim.user.activity.ActivityLogService;
import tn.esprit.marketsim.user.activity.ActivityOutcome;
import tn.esprit.marketsim.user.activity.TraceDetails;
import tn.esprit.marketsim.user.dto.AuthDtos.UpdateProfileRequest;
import tn.esprit.marketsim.user.dto.AuthDtos.UserProfileResponse;
import tn.esprit.marketsim.user.level.UserLevelService;
import tn.esprit.marketsim.user.level.dto.UserLevelDtos.UserLevelResponse;
import tn.esprit.marketsim.user.score.SecurityScoreService;
import tn.esprit.marketsim.user.score.dto.SecurityScoreDtos.SecurityScoreResponse;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class UserController {

    static final String SELF_ACTION_FORBIDDEN = "SELF_ACTION_FORBIDDEN";
    static final String USER_HAS_DEPENDENT_DATA = "USER_HAS_DEPENDENT_DATA";
    static final String LAST_ADMIN = "LAST_ADMIN";

    private final UserRepository userRepository;
    private final UserService userService;
    private final ActivityLogService activityLogService;
    private final SecurityScoreService securityScoreService;
    private final UserLevelService userLevelService;

    @GetMapping("/users/me")
    public User me(@AuthenticationPrincipal User user) {
        return user;
    }

    @GetMapping("/users/me/security-score")
    public SecurityScoreResponse mySecurityScore(@AuthenticationPrincipal User user) {
        return securityScoreService.recomputeOwn(user.getId());
    }

    @GetMapping("/users/me/level")
    public UserLevelResponse myLevel(@AuthenticationPrincipal User user) {
        return userLevelService.recomputeOwn(user.getId());
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

    @GetMapping("/admin/users/{userId}/level")
    public UserLevelResponse userLevel(@PathVariable Long userId) {
        return userLevelService.recomputeForAdmin(userId);
    }

    @PatchMapping("/admin/users/{id}/enabled")
    public User setEnabled(
            @AuthenticationPrincipal User admin,
            @PathVariable Long id,
            @RequestParam boolean value) {
        if (!value && admin.getId().equals(id)) {
            activityLogService.recordImmediate(
                    admin.getId(), id, ActivityAction.ADMIN_SET_ENABLED, ActivityOutcome.FAILURE,
                    SELF_ACTION_FORBIDDEN, TraceDetails.empty());
            throw new IllegalArgumentException("Vous ne pouvez pas desactiver votre propre compte");
        }
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

    @PatchMapping("/admin/users/{id}/role")
    public User setRole(
            @AuthenticationPrincipal User admin,
            @PathVariable Long id,
            @RequestParam Role value) {
        if (admin.getId().equals(id)) {
            failRole(admin, id, SELF_ACTION_FORBIDDEN);
            throw new IllegalArgumentException("Vous ne pouvez pas modifier votre propre role");
        }
        User user = userRepository.findById(id).orElse(null);
        if (user == null) {
            failRole(admin, id, "USER_NOT_FOUND");
            throw new NotFoundException("Utilisateur introuvable");
        }
        Role previous = user.getRole();
        if (previous == value) {
            activityLogService.recordImmediate(
                    admin.getId(), id, ActivityAction.ADMIN_SET_ROLE, ActivityOutcome.SUCCESS, "UNCHANGED",
                    TraceDetails.empty());
            return user;
        }
        if (previous == Role.ADMIN && user.isEnabled()
                && userRepository.countByRoleAndEnabled(Role.ADMIN, true) <= 1) {
            failRole(admin, id, LAST_ADMIN);
            throw new ConflictException("Impossible de retirer le dernier administrateur actif");
        }
        user.setRole(value);
        User saved = userRepository.save(user);
        TraceDetails details = TraceDetails.empty().add("role", previous.name(), value.name());
        activityLogService.recordAfterCommit(
                admin.getId(), id, ActivityAction.ADMIN_SET_ROLE, value == Role.ADMIN ? "PROMOTED" : "DEMOTED",
                details);
        return saved;
    }

    private void failRole(User admin, Long id, String reasonCode) {
        activityLogService.recordImmediate(
                admin.getId(), id, ActivityAction.ADMIN_SET_ROLE, ActivityOutcome.FAILURE, reasonCode,
                TraceDetails.empty());
    }

    @DeleteMapping("/admin/users/{id}")
    public void delete(@AuthenticationPrincipal User admin, @PathVariable Long id) {
        if (admin.getId().equals(id)) {
            activityLogService.recordImmediate(
                    admin.getId(), id, ActivityAction.ADMIN_DELETE_USER, ActivityOutcome.FAILURE,
                    SELF_ACTION_FORBIDDEN, TraceDetails.empty());
            throw new IllegalArgumentException("Vous ne pouvez pas supprimer votre propre compte");
        }
        if (!userRepository.existsById(id)) {
            activityLogService.recordImmediate(
                    admin.getId(), id, ActivityAction.ADMIN_DELETE_USER, ActivityOutcome.FAILURE, "USER_NOT_FOUND",
                    TraceDetails.empty());
            throw new NotFoundException("Utilisateur introuvable");
        }
        try {
            userRepository.deleteById(id);
        } catch (DataIntegrityViolationException ex) {
            // Ordres, strategies, agents... d'autres modules referencent encore ce compte.
            activityLogService.recordImmediate(
                    admin.getId(), id, ActivityAction.ADMIN_DELETE_USER, ActivityOutcome.FAILURE,
                    USER_HAS_DEPENDENT_DATA, TraceDetails.empty());
            throw new ConflictException(
                    "Ce compte possede des donnees liees (ordres, strategies, agents...). Desactivez-le plutot que de le supprimer");
        }
        TraceDetails details = TraceDetails.empty().add("accountDeleted", false, true);
        activityLogService.recordAfterCommit(admin.getId(), id, ActivityAction.ADMIN_DELETE_USER, "OK", details);
    }
}
