package tn.esprit.marketsim.user.settings;

import org.springframework.data.jpa.repository.JpaRepository;

public interface GlobalUserSettingsRepository extends JpaRepository<GlobalUserSettings, Long> {
}
