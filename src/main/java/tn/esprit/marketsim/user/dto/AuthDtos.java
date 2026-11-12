package tn.esprit.marketsim.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import tn.esprit.marketsim.user.User;

/** DTOs du module 1 (records Java = classes immuables tres courtes). */
public final class AuthDtos {

    private AuthDtos() {
    }

    public record RegisterRequest(
            @NotBlank String firstName,
            @NotBlank String lastName,
            @NotBlank @Email String email,
            @NotBlank @Size(min = 6, message = "6 caracteres minimum") String password) {
    }

    public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {
    }

    public record AuthResponse(String token, User user) {
    }

    /** Champs absents (null) : inchanges. Le mot de passe change seulement si les deux champs sont envoyes. */
    public record UpdateProfileRequest(
            String firstName,
            String lastName,
            String email,
            String currentPassword,
            String newPassword) {
    }

    /**
     * Profil renvoye apres mise a jour : aucun mot de passe ni hash.
     * token est renseigne seulement si l'email a change (le JWT contient l'email).
     */
    public record UserProfileResponse(Long id, String firstName, String lastName, String email, String token) {
    }
}
