package tn.esprit.marketsim.common;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.user.User;
import tn.esprit.marketsim.user.UserRepository;
import tn.esprit.marketsim.user.activity.ActivityAction;
import tn.esprit.marketsim.user.activity.ActivityLogService;
import tn.esprit.marketsim.user.activity.ActivityOutcome;
import tn.esprit.marketsim.user.activity.TraceDetails;
import tn.esprit.marketsim.user.anomaly.LoginAnomalyModel;
import tn.esprit.marketsim.user.anomaly.LoginAnomalyService;
import tn.esprit.marketsim.user.anomaly.LoginRequestContext;
import tn.esprit.marketsim.user.dto.AuthDtos.LoginRequest;

/** Transforme les exceptions en reponses JSON lisibles par le frontend. */
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private static final Pattern ADMIN_USER_ENABLED =
            Pattern.compile(".*/api/admin/users/([^/]+)/enabled/?$");
    private static final Pattern ADMIN_USER_DELETE =
            Pattern.compile(".*/api/admin/users/([^/]+)/?$");

    private final ActivityLogService activityLogService;
    private final LoginAnomalyService loginAnomalyService;
    private final LoginRequestContext loginRequestContext;
    private final UserRepository userRepository;

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(NotFoundException ex) {
        return body(HttpStatus.NOT_FOUND, ex.getMessage(), null);
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<Map<String, Object>> conflict(ConflictException ex) {
        return body(HttpStatus.CONFLICT, ex.getMessage(), null);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<Map<String, Object>> optimisticLock(OptimisticLockingFailureException ex) {
        return body(HttpStatus.CONFLICT, "Parametres modifies ailleurs — rechargez avant de reenregistrer", null);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException ex) {
        return body(HttpStatus.BAD_REQUEST, ex.getMessage(), null);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException ex, HttpServletRequest request) {
        String message = "JSON invalide";
        String reason = "INVALID_JSON";
        Throwable cause = ex.getMostSpecificCause();
        if (cause != null && cause.getMessage() != null
                && cause.getMessage().toLowerCase().contains("float")) {
            message = "Valeurs entieres requises (pas de decimales)";
            reason = "NON_INTEGER_VALUE";
        }
        logPreServiceFailure(request, reason, null);
        return body(HttpStatus.BAD_REQUEST, message, null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> validation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(e -> errors.put(e.getField(), e.getDefaultMessage()));
        logPreServiceFailure(request, "VALIDATION_FAILED", ex.getBindingResult().getTarget());
        return body(HttpStatus.BAD_REQUEST, "Donnees invalides", errors);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> missingParam(
            MissingServletRequestParameterException ex, HttpServletRequest request) {
        logPreServiceFailure(request, "MISSING_PARAMETER", null);
        return body(HttpStatus.BAD_REQUEST, "Parametre manquant : " + ex.getParameterName(), null);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> typeMismatch(
            MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        String reason = "id".equals(ex.getName()) ? "INVALID_TARGET_ID" : "INVALID_PARAMETER";
        logPreServiceFailure(request, reason, null);
        return body(HttpStatus.BAD_REQUEST, "Parametre invalide : " + ex.getName(), null);
    }

    private void logPreServiceFailure(HttpServletRequest request, String reasonCode, Object bindingTarget) {
        if (request == null || request.getRequestURI() == null) {
            return;
        }
        String path = request.getRequestURI();
        String method = request.getMethod() == null ? "" : request.getMethod().toUpperCase();
        Long actorId = currentActorId();

        if ("POST".equals(method) && path.matches(".*/api/auth/login/?$")) {
            Instant attemptAt = Instant.now();
            String userAgent = loginRequestContext.userAgent();
            String deviceHint = loginRequestContext.deviceHint();
            Long loginActor = resolveLoginActor(bindingTarget);
            TraceDetails browserMeta = TraceDetails.empty()
                    .add("browserFamily", null, LoginAnomalyModel.normalizeBrowserFamily(userAgent));
            Long logId = activityLogService.recordImmediate(
                    loginActor, loginActor, ActivityAction.LOGIN, ActivityOutcome.FAILURE, reasonCode,
                    browserMeta, attemptAt);
            loginAnomalyService.assessLogin(logId, loginActor, true, userAgent, deviceHint, attemptAt);
            return;
        }

        if (("PATCH".equals(method) || "PUT".equals(method)) && path.matches(".*/api/users/me/?$")) {
            activityLogService.recordImmediate(
                    actorId, actorId, ActivityAction.PROFILE_UPDATE, ActivityOutcome.FAILURE, reasonCode,
                    TraceDetails.empty());
            return;
        }

        Matcher enabled = ADMIN_USER_ENABLED.matcher(path);
        if ("PATCH".equals(method) && enabled.matches()) {
            activityLogService.recordImmediate(
                    actorId, parseTargetId(enabled.group(1)), ActivityAction.ADMIN_SET_ENABLED,
                    ActivityOutcome.FAILURE, reasonCode, TraceDetails.empty());
            return;
        }

        Matcher deleted = ADMIN_USER_DELETE.matcher(path);
        if ("DELETE".equals(method) && deleted.matches()) {
            String segment = deleted.group(1);
            if (!"users".equals(segment)) {
                activityLogService.recordImmediate(
                        actorId, parseTargetId(segment), ActivityAction.ADMIN_DELETE_USER,
                        ActivityOutcome.FAILURE, reasonCode, TraceDetails.empty());
                return;
            }
        }

        if ("PUT".equals(method) && path.endsWith("/api/admin/settings/users")) {
            activityLogService.recordImmediate(
                    actorId, null, ActivityAction.SETTINGS_UPDATE, ActivityOutcome.FAILURE, reasonCode,
                    TraceDetails.empty());
        }
    }

    private Long resolveLoginActor(Object bindingTarget) {
        if (!(bindingTarget instanceof LoginRequest req) || req.email() == null || req.email().isBlank()) {
            return null;
        }
        return userRepository.findByEmail(req.email()).map(User::getId).orElse(null);
    }

    private static Long parseTargetId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(raw);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Long currentActorId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof User user) {
            return user.getId();
        }
        return null;
    }

    private ResponseEntity<Map<String, Object>> body(HttpStatus status, String message, Object details) {
        Map<String, Object> map = new HashMap<>();
        map.put("timestamp", Instant.now().toString());
        map.put("status", status.value());
        map.put("message", message);
        if (details != null) {
            map.put("errors", details);
        }
        return ResponseEntity.status(status).body(map);
    }
}
