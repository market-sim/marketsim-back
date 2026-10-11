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

    public record UpdateProfileRequest(@NotBlank String firstName, @NotBlank String lastName) {
    }
}
