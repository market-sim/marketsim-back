package tn.esprit.marketsim.user.stats;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.user.stats.dto.AdminStatsDtos.AdminStatsResponse;

/** Supervision : /api/admin/** est reserve au role ADMIN par SecurityConfig. */
@RestController
@RequestMapping("/api/admin/stats")
@RequiredArgsConstructor
public class AdminStatsController {

    private final AdminStatsService adminStatsService;

    @GetMapping
    public AdminStatsResponse stats(@RequestParam(defaultValue = "7") int days) {
        return adminStatsService.stats(days);
    }
}
