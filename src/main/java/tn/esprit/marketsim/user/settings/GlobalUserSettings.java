package tn.esprit.marketsim.user.settings;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Ligne unique (id = 1) des parametres globaux du module Utilisateurs. */
@Entity
@Table(name = "global_user_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GlobalUserSettings {

    public static final long SINGLETON_ID = 1L;
    public static final boolean DEFAULT_REGISTRATION_ENABLED = true;
    public static final int DEFAULT_MAX_FAILED_LOGIN_ATTEMPTS = 5;
    public static final int DEFAULT_LOCK_DURATION_MINUTES = 15;

    @Id
    private Long id;

    @Column(nullable = false)
    private boolean registrationEnabled;

    @Column(nullable = false)
    private int maxFailedLoginAttempts;

    @Column(nullable = false)
    private int lockDurationMinutes;

    @Version
    private long version;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    public static GlobalUserSettings defaults() {
        return GlobalUserSettings.builder()
                .id(SINGLETON_ID)
                .registrationEnabled(DEFAULT_REGISTRATION_ENABLED)
                .maxFailedLoginAttempts(DEFAULT_MAX_FAILED_LOGIN_ATTEMPTS)
                .lockDurationMinutes(DEFAULT_LOCK_DURATION_MINUTES)
                .build();
    }

    @PrePersist
    @PreUpdate
    void touch() {
        if (id == null) {
            id = SINGLETON_ID;
        }
        updatedAt = LocalDateTime.now();
    }
}
