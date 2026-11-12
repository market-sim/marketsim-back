package tn.esprit.marketsim.user.activity;

import java.time.Instant;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.user.activity.dto.ActivityLogDtos.ActivityLogDetailResponse;
import tn.esprit.marketsim.user.activity.dto.ActivityLogDtos.ActivityLogPageResponse;

@RestController
@RequestMapping("/api/admin/activity-logs")
@RequiredArgsConstructor
public class AdminActivityLogController {

    private final ActivityLogService activityLogService;

    @GetMapping
    public ActivityLogPageResponse list(
            @RequestParam(required = false) Long actorId,
            @RequestParam(required = false) ActivityAction action,
            @RequestParam(required = false) ActivityOutcome outcome,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return activityLogService.search(actorId, action, outcome, from, to, page, size);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ActivityLogDetailResponse> detail(@PathVariable Long id) {
        ActivityLogDetailResponse body = activityLogService.getById(id);
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok();
        if (body.correlationId() != null) {
            builder.header(OperationContext.CORRELATION_HEADER, body.correlationId());
        }
        return builder.body(body);
    }
}
