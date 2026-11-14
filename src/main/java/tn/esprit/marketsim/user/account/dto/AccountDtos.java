package tn.esprit.marketsim.user.account.dto;

import java.time.Instant;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import tn.esprit.marketsim.user.Role;

public final class AccountDtos {

    private AccountDtos() {
    }

    public record ForgotPasswordRequest(@NotBlank @Email String email) {
    }

    public record ResetPasswordRequest(@NotBlank String token,
                                       @NotBlank @Size(min = 6, message = "6 caracteres minimum") String newPassword) {
    }

    public record VerifyEmailRequest(@NotBlank String token) {
    }

    public record TwoFactorLoginRequest(@NotBlank String challenge, @NotBlank String code) {
    }

    /** Secret a saisir (ou QR code) dans l'application d'authentification, avant activation. */
    public record TwoFactorSetupResponse(String secret, String otpauthUri) {
    }

    public record TwoFactorCodeRequest(@NotBlank String code) {
    }

    public record TwoFactorDisableRequest(@NotBlank String password, @NotBlank String code) {
    }

    public record DeleteAccountRequest(@NotBlank String password) {
    }

    public record SessionView(String id, String device, String ipAddress, Instant createdAt, Instant lastSeenAt,
                              Instant expiresAt, boolean current) {
    }

    public record MessageResponse(String message) {
    }

    public record AccountStatus(boolean emailConfirmed, boolean twoFactorEnabled, int activeSessions) {
    }

    /** Creation d'un compte par l'administrateur ; sans mot de passe, un mot de passe provisoire est genere. */
    public record AdminCreateUserRequest(@NotBlank String firstName, @NotBlank String lastName,
                                         @NotBlank @Email String email, Role role,
                                         @Size(min = 6, message = "6 caracteres minimum") String password) {
    }

    /** Mot de passe provisoire affiche une seule fois a l'administrateur. */
    public record TemporaryPasswordResponse(Long userId, String email, String temporaryPassword, String message) {
    }

    public record MailView(Long id, String recipient, String subject, String body, String actionUrl, String kind,
                           Instant createdAt, String status, String error) {
    }
}
