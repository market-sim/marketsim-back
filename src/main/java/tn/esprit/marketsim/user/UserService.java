package tn.esprit.marketsim.user;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.common.ConflictException;
import tn.esprit.marketsim.common.NotFoundException;
import tn.esprit.marketsim.security.JwtService;
import tn.esprit.marketsim.user.activity.ActivityAction;
import tn.esprit.marketsim.user.activity.ActivityLogService;
import tn.esprit.marketsim.user.activity.ActivityOutcome;
import tn.esprit.marketsim.user.activity.TraceDetails;
import tn.esprit.marketsim.user.dto.AuthDtos.UpdateProfileRequest;
import tn.esprit.marketsim.user.dto.AuthDtos.UserProfileResponse;

@Service
@RequiredArgsConstructor
public class UserService {

    private static final int MIN_PASSWORD_LENGTH = 6;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final ActivityLogService activityLogService;

    @Transactional
    public UserProfileResponse updateProfile(Long userId, UpdateProfileRequest req) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            failProfile(userId, userId, "USER_NOT_FOUND");
            throw new NotFoundException("Utilisateur introuvable");
        }
        String previousEmail = user.getEmail();
        boolean passwordChanged = false;

        if (req.firstName() != null) {
            String firstName = req.firstName().trim();
            if (firstName.isEmpty()) {
                failProfile(userId, userId, "BLANK_FIRST_NAME");
                throw new IllegalArgumentException("Le prenom ne peut pas etre vide");
            }
            user.setFirstName(firstName);
        }
        if (req.lastName() != null) {
            String lastName = req.lastName().trim();
            if (lastName.isEmpty()) {
                failProfile(userId, userId, "BLANK_LAST_NAME");
                throw new IllegalArgumentException("Le nom ne peut pas etre vide");
            }
            user.setLastName(lastName);
        }
        if (req.email() != null) {
            String email = req.email().trim();
            if (!email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
                failProfile(userId, userId, "INVALID_EMAIL");
                throw new IllegalArgumentException("Email invalide");
            }
            if (userRepository.existsByEmailIgnoreCaseAndIdNot(email, user.getId())) {
                failProfile(userId, userId, "EMAIL_TAKEN");
                throw new ConflictException("Cet email est deja utilise");
            }
            user.setEmail(email);
        }

        String currentPassword = blankToNull(req.currentPassword());
        String newPassword = blankToNull(req.newPassword());
        if (currentPassword != null || newPassword != null) {
            if (currentPassword == null || newPassword == null) {
                failProfile(userId, userId, "PASSWORD_FIELDS_REQUIRED");
                throw new IllegalArgumentException("Le mot de passe actuel et le nouveau mot de passe sont requis");
            }
            if (newPassword.length() < MIN_PASSWORD_LENGTH) {
                failProfile(userId, userId, "PASSWORD_TOO_SHORT");
                throw new IllegalArgumentException("6 caracteres minimum");
            }
            if (!passwordEncoder.matches(currentPassword, user.getPassword())) {
                failProfile(userId, userId, "INVALID_CURRENT_PASSWORD");
                throw new IllegalArgumentException("Mot de passe actuel incorrect");
            }
            user.setPassword(passwordEncoder.encode(newPassword));
            passwordChanged = true;
        }

        try {
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException ex) {
            failProfile(userId, userId, "EMAIL_TAKEN");
            throw new ConflictException("Cet email est deja utilise");
        }

        TraceDetails details = TraceDetails.empty()
                .add("email", previousEmail, user.getEmail())
                .passwordChanged(passwordChanged);
        activityLogService.recordAfterCommit(userId, userId, ActivityAction.PROFILE_UPDATE, "OK", details);

        String token = previousEmail.equals(user.getEmail()) ? null : jwtService.generateToken(user);
        return new UserProfileResponse(user.getId(), user.getFirstName(), user.getLastName(), user.getEmail(), token);
    }

    private void failProfile(Long actorId, Long targetUserId, String reasonCode) {
        activityLogService.recordImmediate(
                actorId, targetUserId, ActivityAction.PROFILE_UPDATE, ActivityOutcome.FAILURE, reasonCode,
                TraceDetails.empty());
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }
}
