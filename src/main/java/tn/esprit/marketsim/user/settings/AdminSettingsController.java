package tn.esprit.marketsim.user.settings;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.user.settings.dto.GlobalUserSettingsDtos.GlobalUserSettingsResponse;
import tn.esprit.marketsim.user.settings.dto.GlobalUserSettingsDtos.UpdateGlobalUserSettingsRequest;

@RestController
@RequestMapping("/api/admin/settings")
@RequiredArgsConstructor
public class AdminSettingsController {

    private final GlobalUserSettingsService settingsService;

    @GetMapping("/users")
    public GlobalUserSettingsResponse get() {
        return settingsService.get();
    }

    @PutMapping("/users")
    public GlobalUserSettingsResponse update(@Valid @RequestBody UpdateGlobalUserSettingsRequest req) {
        return settingsService.update(req);
    }
}
