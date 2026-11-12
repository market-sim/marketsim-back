package tn.esprit.marketsim.user.settings.dto;

import java.time.LocalDateTime;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import tn.esprit.marketsim.common.StrictIntDeserializer;
import tn.esprit.marketsim.common.StrictLongDeserializer;

public final class GlobalUserSettingsDtos {

    private GlobalUserSettingsDtos() {
    }

    public record GlobalUserSettingsResponse(
            boolean registrationEnabled,
            int maxFailedLoginAttempts,
            int lockDurationMinutes,
            long version,
            LocalDateTime updatedAt) {
    }

    public record UpdateGlobalUserSettingsRequest(
            @NotNull Boolean registrationEnabled,
            @NotNull @Min(3) @Max(10)
            @JsonDeserialize(using = StrictIntDeserializer.class)
            Integer maxFailedLoginAttempts,
            @NotNull @Min(1) @Max(60)
            @JsonDeserialize(using = StrictIntDeserializer.class)
            Integer lockDurationMinutes,
            @NotNull
            @JsonDeserialize(using = StrictLongDeserializer.class)
            Long version) {
    }

    public record RegistrationStatusResponse(boolean registrationEnabled) {
    }
}
