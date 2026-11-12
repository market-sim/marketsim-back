package tn.esprit.marketsim.user.anomaly;

import java.time.Instant;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.user.anomaly.dto.LoginAnomalyDtos.LoginAnomalyDetailResponse;
import tn.esprit.marketsim.user.anomaly.dto.LoginAnomalyDtos.LoginAnomalyPageResponse;

@RestController
@RequestMapping("/api/admin/login-anomalies")
@RequiredArgsConstructor
public class AdminLoginAnomalyController {

    private final LoginAnomalyService loginAnomalyService;

    @GetMapping
    public LoginAnomalyPageResponse list(
            @RequestParam(required = false) Long actorId,
            @RequestParam(required = false) Long activityLogId,
            @RequestParam(required = false) LoginRiskLevel riskLevel,
            @RequestParam(required = false) LoginAnomalyStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return loginAnomalyService.search(actorId, activityLogId, riskLevel, status, from, to, page, size);
    }

    @GetMapping("/{id}")
    public LoginAnomalyDetailResponse detail(@PathVariable Long id) {
        return loginAnomalyService.getById(id);
    }
}
